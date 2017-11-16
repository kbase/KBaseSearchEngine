package kbasesearchengine.parse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;

import kbasesearchengine.common.GUID;
import kbasesearchengine.common.ObjectJsonPath;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.search.ObjectData;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.ValidationException;
import us.kbase.common.service.UObject;

public class KeywordParser {
    
    //TODO EXP handle all exceptions
    
    public static ParsedObject extractKeywords(
            final String type,
            final String json,
            final String parentJson,
            final List<IndexingRules> indexingRules, 
            final ObjectLookupProvider lookup,
            final List<GUID> objectRefPath)
            throws IOException, ObjectParseException, IndexingException, InterruptedException {

        // check pre-conditons
        if(json == null) throw new IllegalArgumentException("json is a required parameter");
        if(indexingRules == null) throw new IllegalArgumentException("indexingRules is a required parameter");

        for(IndexingRules rule: indexingRules) {
            try {
                rule.validate();
            } catch(ValidationException ex) {
                throw new IllegalArgumentException("Unable to extract keywords", ex);
            }
        }

        Map<String, InnerKeyValue> keywords = new LinkedHashMap<>();
        ValueConsumer<List<IndexingRules>> consumer = new ValueConsumer<List<IndexingRules>>() {
            @Override
            public void addValue(List<IndexingRules> rulesList, Object value)
                    throws IndexingException, InterruptedException, ObjectParseException {
                for (IndexingRules rule : rulesList) {
                    processRule(type, rule, getKeyName(rule), value, keywords, lookup, 
                            objectRefPath);
                }
            }
        };
        // Sub-objects
        extractIndexingPart(json, false, indexingRules, consumer);
        // Parent
        if (parentJson != null) {
            extractIndexingPart(parentJson, true, indexingRules, consumer);
        }
        Map<String, List<IndexingRules>> ruleMap = indexingRules.stream().collect(
                Collectors.groupingBy(rule -> getKeyName(rule)));
        for (String key : ruleMap.keySet()) {
            for (IndexingRules rule : ruleMap.get(key)) {
                if (!rule.isDerivedKey()) {
                    // Let's check that not derived keywords are all set (with optional defaults)
                    List<Object> values = keywords.containsKey(key) ? keywords.get(key).values : 
                        null;
                    if (isEmpty(values)) {
                        processRule(type, rule, key, null, keywords, lookup, objectRefPath);
                    }
                }
            }
        }
        for (String key : ruleMap.keySet()) {
            for (IndexingRules rule : ruleMap.get(key)) {
                if (rule.isDerivedKey()) {
                    processDerivedRule(type, key, rule, ruleMap, keywords, lookup, 
                            new LinkedHashSet<>(), objectRefPath);
                }
            }
        }
        ParsedObject ret = new ParsedObject();
        ret.json = json;
        ret.keywords = keywords.entrySet().stream().filter(kv -> !kv.getValue().notIndexed)
                .collect(Collectors.toMap(kv -> kv.getKey(), kv -> kv.getValue().values));
        return ret;
    }

    private static List<Object> processDerivedRule(String type, 
            Map<String, List<IndexingRules>> ruleMap, String key, 
            Map<String, InnerKeyValue> keywords, ObjectLookupProvider lookup, 
            Set<String> keysWaitingInStack, List<GUID> callerRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        if (!ruleMap.containsKey(key)) {
            throw new IllegalStateException("Unknown source-key in derived keywords: " + 
                    type + "/" + key);
        }
        List<Object> ret = null;
        for (IndexingRules rule : ruleMap.get(key)) {
            ret = processDerivedRule(type, key, rule, ruleMap, keywords, lookup, 
                    keysWaitingInStack, callerRefPath);
        }
        return ret;
    }
    
    private static List<Object> processDerivedRule(String type, String key, IndexingRules rule,
            Map<String, List<IndexingRules>> ruleMap, Map<String, InnerKeyValue> keywords, 
            ObjectLookupProvider lookup, Set<String> keysWaitingInStack,
            List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        if (!ruleMap.containsKey(key) || rule == null) {
            throw new IllegalStateException("Unknown source-key in derived keywords: " + 
                    type + "/" + key);
        }
        if (keywords.containsKey(key)) {
            return keywords.get(key).values;
        }
        if (keysWaitingInStack.contains(key)) {
            throw new IllegalStateException("Circular dependency in derived keywords: " +
                    type + " / " + keysWaitingInStack);
        }
        if (!rule.isDerivedKey()) {
            throw new IllegalStateException("Reference to not derived keyword with no value: " +
                    type + "/" + key);
        }
        keysWaitingInStack.add(key);
        String sourceKey = rule.getSourceKey();
        if (sourceKey == null) {
            throw new IllegalStateException("Source-key not defined for derived keyword: " + 
                    type + "/" + key);
        }
        List<Object> values = processDerivedRule(type, ruleMap, sourceKey, keywords, lookup, 
                keysWaitingInStack, objectRefPath);
        if (rule.getSubobjectIdKey() != null) {
            processDerivedRule(type, ruleMap, rule.getSubobjectIdKey(), keywords, lookup, 
                    keysWaitingInStack, objectRefPath);
        }
        for (Object value : values) {
            processRule(type, rule, key, value, keywords, lookup, objectRefPath);
        }
        keysWaitingInStack.remove(key);
        List<Object> ret = keywords.containsKey(key) ? keywords.get(key).values : new ArrayList<>();
        if (isEmpty(ret) && rule.getOptionalDefaultValue() != null) {
            addOrAddAll(rule.getOptionalDefaultValue(), ret);
        }
        return ret;
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof List && ((List<?>)value).isEmpty());
    }
    
    private static void processRule(String type, IndexingRules rule, String key, Object value,
            Map<String, InnerKeyValue> keywords, ObjectLookupProvider lookup,
            List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        Object valueFinal = value;
        if (valueFinal == null) {
            if (rule.getOptionalDefaultValue() != null) {
                valueFinal = rule.getOptionalDefaultValue();
            } else {
                valueFinal = Collections.EMPTY_LIST;
            }
        }
        InnerKeyValue values = keywords.get(key);
        if (values == null) {
            values = new InnerKeyValue();
            values.values = new ArrayList<>();
            keywords.put(key, values);
        }
        values.notIndexed = rule.isNotIndexed();
        if (rule.getTransform() != null) {
            valueFinal = transform(valueFinal, rule.getTransform(), rule, keywords,
                    lookup, objectRefPath);
        }
        addOrAddAll(valueFinal, values.values);
    }
    
    public static String getKeyName(IndexingRules rules) {
        return rules.getKeyName() != null ? rules.getKeyName():
            rules.getPath().getPathItems()[0];
    }

    @SuppressWarnings("unchecked")
    private static void addOrAddAll(Object valueFinal, List<Object> values) {
        if (valueFinal != null) {
            if (valueFinal instanceof List) {
                values.addAll((List<Object>)valueFinal);
            } else {
                values.add(valueFinal);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object transform(Object value, String transform, IndexingRules rule,
            Map<String, InnerKeyValue> sourceKeywords, ObjectLookupProvider lookup,
            List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        String retProp = null;
        if (transform.contains(".")) {
            int dotPos = transform.indexOf('.');
            retProp = transform.substring(dotPos + 1);
            transform = transform.substring(0, dotPos);
        }
        switch (transform) {
        case "location":
            List<List<Object>> loc = (List<List<Object>>)value;
            Map<String, Object> retLoc = new LinkedHashMap<>();
            retLoc.put("contig_id", loc.get(0).get(0));
            String strand = (String)loc.get(0).get(2);
            retLoc.put("strand", strand);
            int start = (Integer)loc.get(0).get(1);
            int len = (Integer)loc.get(0).get(3);
            retLoc.put("length", len);
            retLoc.put("start", strand.equals("+") ? start : (start - len + 1));
            retLoc.put("stop", strand.equals("+") ? (start + len - 1) : start);
            if (retProp == null) {
                return retLoc;
            }
            return retLoc.get(retProp);
        case "values":
            if (value == null) {
                return null;
            }
            if (value instanceof List) {
                List<Object> input = (List<Object>)value;
                List<Object> ret = new ArrayList<>();
                for (Object item : input) {
                    addOrAddAll(transform(item, transform, rule, sourceKeywords, lookup, 
                            objectRefPath), ret);
                }
                return ret;
            }
            if (value instanceof Map) {
                Map<String, Object> input = (Map<String, Object>)value;
                List<Object> ret = new ArrayList<>();
                for (Object item : input.values()) {
                    addOrAddAll(transform(item, transform, rule, sourceKeywords, lookup,
                            objectRefPath), ret);
                }
                return ret;
            }
            return String.valueOf(value);
        case "string":
            return String.valueOf(value);
        case "integer":
            return Integer.parseInt(String.valueOf(value));
        case "guid":
            String type = rule.getTargetObjectType();
            if (type == null) {
                throw new IllegalStateException("Target object type should be set for 'guid' " +
                        "transform");
            }
            final ObjectTypeParsingRules typeDescr = lookup.getTypeDescriptor(type);
            final String storageCode = typeDescr.getStorageObjectType().getStorageCode();
            final Set<String> refs = toStringSet(value);
            final Set<GUID> unresolvedGUIDs;
            try {
                unresolvedGUIDs = refs.stream().map(r -> GUID.fromRef(storageCode, r))
                        .collect(Collectors.toSet());
            } catch (IllegalArgumentException e) {
                throw new ObjectParseException(e.getMessage(), e);
            }
            Set<GUID> guids = lookup.resolveRefs(objectRefPath, unresolvedGUIDs);
            Set<String> subIds = null;
            if (rule.getSubobjectIdKey() != null) {
                if (typeDescr.getInnerSubType() == null) {
                    throw new IllegalStateException("Subobject GUID transform should correspond " +
                            "to subobject type descriptor: " + rule);
                }
                subIds = toStringSet(sourceKeywords.get(rule.getSubobjectIdKey()).values);
                if (guids.size() != 1) {
                    throw new IllegalStateException("In subobject IDs case source keyword " + 
                            "should point to value with only one parent object reference");
                }
                GUID parentGuid = guids.iterator().next();
                guids = new LinkedHashSet<>();
                for (String subId : subIds) {
                    guids.add(new GUID(typeDescr.getStorageObjectType().getStorageCode(),
                            parentGuid.getAccessGroupId(),
                            parentGuid.getAccessGroupObjectId(), parentGuid.getVersion(), 
                            typeDescr.getInnerSubType(), subId));
                }
            }
            Map<GUID, String> guidToType = lookup.getTypesForGuids(guids);
            for (GUID guid : guids) {
                if (!guidToType.containsKey(guid)) {
                    throw new IllegalStateException("GUID " + guid + " not found");
                }
                String actualType = guidToType.get(guid);
                if (!actualType.equals(type)) {
                    throw new IllegalStateException("GUID " + guid + " has unexpected type: " + 
                            actualType);
                }
            }
            return guids.stream().map(GUID::toString).collect(Collectors.toList());
        case "lookup":
            if (retProp == null) {
                throw new IllegalStateException("No sub-property defined for lookup transform");
            }
            Set<String> guidText = toStringSet(value);
            Map<GUID, ObjectData> guidToObj = lookup.lookupObjectsByGuid(
                    guidText.stream().map(GUID::new).collect(Collectors.toSet()));
            List<Object> ret = new ArrayList<>();
            for (ObjectData obj : guidToObj.values()) {
                if (retProp.startsWith("key.")) {
                    String key = retProp.substring(4);
                    ret.add(obj.keyProps.get(key));
                } else if (retProp.equals("oname")) {
                    ret.add(obj.objectName);
                }
            }
            return ret;
        default:
            throw new IllegalStateException("Unsupported transformation type: " + transform);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> toStringSet(Object value) {
        Set<String> refs = new LinkedHashSet<>();
        if (value instanceof List) {
            for (Object obj : (List<Object>)value) {
                refs.add(String.valueOf(obj));
            }
        } else {
            refs.add(String.valueOf(value));
        }
        return refs;
    }
    
    private static void extractIndexingPart(String json, boolean fromParent,
            List<IndexingRules> indexingRules, ValueConsumer<List<IndexingRules>> consumer)
            throws IOException, ObjectParseException, JsonParseException,
            IndexingException, InterruptedException {
        Map<ObjectJsonPath, List<IndexingRules>> pathToRules = new LinkedHashMap<>();
        for (IndexingRules rules : indexingRules) {
            if (rules.isDerivedKey()) {
                continue;
            }
            if (rules.getPath() == null) {
                throw new IllegalStateException("Path should be defined for non-derived " +
                        "indexing rules");
            }
            if (rules.getSubobjectIdKey() != null) {
                throw new IllegalStateException("Subobject ID key can only be set for derived " +
                        "keywords: " + getKeyName(rules));
            }
            if (rules.isFromParent() != fromParent) {
                continue;
            }
            if (rules.isFullText() || rules.getKeywordType() != null) {
                List<IndexingRules> rulesList = pathToRules.get(rules.getPath());
                if (rulesList == null) {
                    rulesList = new ArrayList<>();
                    pathToRules.put(rules.getPath(), rulesList);
                }
                rulesList.add(rules);
            }
        }
        ValueCollectingNode<List<IndexingRules>> root = new ValueCollectingNode<>();
        for (ObjectJsonPath path : pathToRules.keySet()) {
            root.addPath(path, pathToRules.get(path));
        }
        ValueCollector<List<IndexingRules>> collector = new ValueCollector<List<IndexingRules>>();
        try (JsonParser jp = UObject.getMapper().getFactory().createParser(json)) {
            collector.mapKeys(root, jp, consumer);
        }
    }

    public interface ObjectLookupProvider {
        public Set<GUID> resolveRefs(List<GUID> objectRefPath, Set<GUID> unresolvedGUIDs) 
                throws IndexingException, InterruptedException;
        public Map<GUID, String> getTypesForGuids(Set<GUID> guids)
                throws InterruptedException, IndexingException;
        public Map<GUID, ObjectData> lookupObjectsByGuid(Set<GUID> guids) 
                throws InterruptedException, IndexingException;
        public ObjectTypeParsingRules getTypeDescriptor(String type) throws IndexingException;
    }

    private static class InnerKeyValue {
        boolean notIndexed;
        List<Object> values;
        
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("InnerKeyValue [notIndexed=");
            builder.append(notIndexed);
            builder.append(", values=");
            builder.append(values);
            builder.append("]");
            return builder.toString();
        }
    }
}
