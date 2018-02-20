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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.google.common.base.Optional;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kbasesearchengine.common.GUID;
import kbasesearchengine.common.ObjectJsonPath;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.search.ObjectData;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.LocationTransformType;
import kbasesearchengine.system.NoSuchTypeException;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.SearchObjectType;
import kbasesearchengine.system.Transform;
import kbasesearchengine.tools.Utils;
import us.kbase.common.service.UObject;

public class KeywordParser {
    
    //TODO EXP handle all exceptions
    
    public static ParsedObject extractKeywords(
            final SearchObjectType searchObjectType,
            final String json,
            final String parentJson,
            final List<IndexingRules> indexingRules, 
            final ObjectLookupProvider lookup,
            final List<GUID> objectRefPath)
            throws IOException, ObjectParseException, IndexingException, InterruptedException {

        // check pre-conditons
        Utils.notNullOrEmpty(json, "json is a required parameter");

        Utils.nonNull(indexingRules, "indexingRules is a required parameter");

        Map<String, InnerKeyValue> keywords = new LinkedHashMap<>();
        ValueConsumer<List<IndexingRules>> consumer = new ValueConsumer<List<IndexingRules>>() {
            @Override
            public void addValue(List<IndexingRules> rulesList, Object value)
                    throws IndexingException, InterruptedException, ObjectParseException {
                for (IndexingRules rule : rulesList) {
                    processRule(rule, rule.getKeyName(), value, keywords, lookup, 
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
                Collectors.groupingBy(rule -> rule.getKeyName()));
        for (String key : ruleMap.keySet()) {
            for (IndexingRules rule : ruleMap.get(key)) {
                if (!rule.isDerivedKey()) {
                    // Let's check that not derived keywords are all set (with optional defaults)
                    List<Object> values = keywords.containsKey(key) ? keywords.get(key).values : 
                        null;
                    if (isEmpty(values)) {
                        processRule(rule, key, null, keywords, lookup, objectRefPath);
                    }
                }
            }
        }
        for (String key : ruleMap.keySet()) {
            for (IndexingRules rule : ruleMap.get(key)) {
                if (rule.isDerivedKey()) {
                    processDerivedRule(searchObjectType, key, rule, ruleMap, keywords, lookup, 
                            new LinkedHashSet<>(), objectRefPath);
                }
            }
        }
        return new ParsedObject(json,
                keywords.entrySet().stream().filter(kv -> !kv.getValue().notIndexed)
                        .collect(Collectors.toMap(kv -> kv.getKey(), kv -> kv.getValue().values)));
    }

    private static List<Object> processDerivedRule(
            final SearchObjectType searchObjectType, 
            final Map<String, List<IndexingRules>> ruleMap,
            final String key, 
            final Map<String, InnerKeyValue> keywords,
            final ObjectLookupProvider lookup, 
            final Set<String> keysWaitingInStack,
            final List<GUID> callerRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        if (!ruleMap.containsKey(key)) {
            throw new ObjectParseException("Unknown source-key in derived keywords: " +
                    toVerRep(searchObjectType) + "/" + key);
        }
        List<Object> ret = null;
        for (IndexingRules rule : ruleMap.get(key)) {
            ret = processDerivedRule(searchObjectType, key, rule, ruleMap, keywords, lookup, 
                    keysWaitingInStack, callerRefPath);
        }
        return ret;
    }
    
    private static String toVerRep(final SearchObjectType searchObjectType) {
        return searchObjectType.getType() + "_" + searchObjectType.getVersion();
    }

    private static List<Object> processDerivedRule(
            final SearchObjectType searchObjectType,
            final String key,
            final IndexingRules rule,
            final Map<String, List<IndexingRules>> ruleMap,
            final Map<String, InnerKeyValue> keywords, 
            final ObjectLookupProvider lookup,
            final Set<String> keysWaitingInStack,
            final List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        if (!ruleMap.containsKey(key) || rule == null) {
            throw new ObjectParseException("Unknown source-key in derived keywords: " +
                    toVerRep(searchObjectType) + "/" + key);
        }
        if (keywords.containsKey(key)) {
            return keywords.get(key).values;
        }
        if (keysWaitingInStack.contains(key)) {
            throw new ObjectParseException("Circular dependency in derived keywords: " +
                    toVerRep(searchObjectType) + " / " + keysWaitingInStack);
        }
        if (!rule.isDerivedKey()) {
            throw new ObjectParseException("Reference to not derived keyword with no value: " +
                    toVerRep(searchObjectType) + "/" + key);
        }
        keysWaitingInStack.add(key);
        List<Object> values = processDerivedRule(searchObjectType, ruleMap, rule.getSourceKey().get(),
                keywords, lookup, keysWaitingInStack, objectRefPath);
        if (rule.getTransform().isPresent() &&
                rule.getTransform().get().getSubobjectIdKey().isPresent()) {
            processDerivedRule(searchObjectType, ruleMap, rule.getTransform().get().getSubobjectIdKey().get(),
                    keywords, lookup, keysWaitingInStack, objectRefPath);
        }
        for (Object value : values) {
            processRule(rule, key, value, keywords, lookup, objectRefPath);
        }
        keysWaitingInStack.remove(key);
        List<Object> ret = keywords.containsKey(key) ? keywords.get(key).values : new ArrayList<>();
        if (isEmpty(ret) && rule.getDefaultValue().isPresent()) {
            addOrAddAll(rule.getDefaultValue().get(), ret);
        }
        return ret;
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof List && ((List<?>)value).isEmpty());
    }
    
    private static void processRule(
            final IndexingRules rule,
            final String key,
            final Object value,
            final Map<String, InnerKeyValue> keywords,
            final ObjectLookupProvider lookup,
            final List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        Object valueFinal = value;
        if (valueFinal == null) {
            if (rule.getDefaultValue().isPresent()) {
                valueFinal = rule.getDefaultValue().get();
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
        if (rule.getTransform().isPresent()) {
            valueFinal = transform(valueFinal, rule, keywords, lookup, objectRefPath);
        }
        addOrAddAll(valueFinal, values.values);
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
    private static Object transform(Object value, IndexingRules rule,
            Map<String, InnerKeyValue> sourceKeywords, ObjectLookupProvider lookup,
            List<GUID> objectRefPath)
            throws IndexingException, InterruptedException, ObjectParseException {
        final Transform transform = rule.getTransform().get();
        switch (transform.getType()) {
        case location:
            List<List<Object>> loc = (List<List<Object>>)value;
            Map<LocationTransformType, Object> retLoc = new LinkedHashMap<>();
            //TODO CODE if the subobject stuff in the ObjectParsingRules is left out this throws an indexing exception. Need to figure out cause.
            retLoc.put(LocationTransformType.contig_id, loc.get(0).get(0));
            String strand = (String)loc.get(0).get(2);
            retLoc.put(LocationTransformType.strand, strand);
            int start = (Integer)loc.get(0).get(1);
            int len = (Integer)loc.get(0).get(3);
            retLoc.put(LocationTransformType.length, len);
            retLoc.put(LocationTransformType.start,
                    strand.equals("+") ? start : (start - len + 1));
            retLoc.put(LocationTransformType.stop, strand.equals("+") ? (start + len - 1) : start);
            if (!transform.getLocation().isPresent()) {
                return retLoc;
            }
            return retLoc.get(transform.getLocation().get());
        case values:
            if (value == null) {
                return null;
            }
            if (value instanceof List) {
                List<Object> input = (List<Object>)value;
                List<Object> ret = new ArrayList<>();
                for (Object item : input) {
                    System.out.println("  item");
                    System.out.println(item.toString());
                    addOrAddAll(transform(item, rule, sourceKeywords, lookup, objectRefPath), ret);
                }
                return ret;
            }
            if (value instanceof Map) {
                Map<String, Object> input = (Map<String, Object>)value;
                List<Object> ret = new ArrayList<>();
                for (Object item : input.values()) {
                    addOrAddAll(transform(item, rule, sourceKeywords, lookup, objectRefPath), ret);
                }
                return ret;
            }

            return String.valueOf(value);
        case filter:
            if (value == null) {
                return null;
            }
            // TreeNode treeNode = (TreeNode)value;
            ObjectMapper mapper = new ObjectMapper();
            JsonNode treeNode = mapper.valueToTree(value);
            if (treeNode instanceof JsonNode) {
                // throw new ObjectParseException("at least we have a tree node here!!!");
                // dig the value to match out of the map
                // Map<String, Object> input = (Map<String, Object>)value;


                // now we use the filter value path to dig the filter value out of the tree...
                JsonNode tempNode = treeNode;

                // Get the filter value.
                Optional<ObjectJsonPath> filterValuePath = rule.getFilterPath();

                if (!filterValuePath.isPresent()) {
                    throw new ObjectParseException("Filter missing filter path");
                }
                
                String[] path = filterValuePath.get().getPathItems();
                for (String el : path) {
                    tempNode = tempNode.path(el);
                    if (tempNode.isMissingNode()) {
                        // didn't find a node on this path, so it is a miss.
                        return null;
                    }
                }
                // eap: todo: better way of handling it not being a string... throw exception e.g.
                String filterValue = tempNode.asText();

                // Apply the test to the filter value.
                Optional<Pattern> filterRegex = rule.getFilterRegex();

                if (!filterRegex.isPresent()) {
                    throw new ObjectParseException("Filter missing filter value regex");
                }

                Matcher matcher = filterRegex.get().matcher((String)filterValue);
                if (!matcher.matches()) {
                    return null;
                }
        

                // Get the value to return;

                Optional<ObjectJsonPath> valuePath = rule.getValuePath();

                if (!valuePath.isPresent()) {
                    throw new ObjectParseException("Filter missing the value path");
                }

                tempNode = treeNode;
                path = valuePath.get().getPathItems();
                for (String el : path) {
                    tempNode = tempNode.path(el);
                    if (tempNode.isMissingNode()) {
                        // didn't find a node on this path, so it is a miss.
                        System.out.println("Did not find node at " + el);
                        return null;
                    }
                }
                // eap: todo: better way of handling it not being a string... throw exception e.g.
                String theValue = tempNode.asText();

                return theValue;
            } else {
                throw new ObjectParseException("Filter transform only applies to TreeNode, applied to a: " + value.getClass().getName());
            }            
        case string:
            return String.valueOf(value);
        case integer:
            return Integer.parseInt(String.valueOf(value));
        case guid:
            final SearchObjectType type = transform.getTargetObjectType().get();
            final ObjectTypeParsingRules typeDescr;
            try {
                typeDescr = lookup.getTypeDescriptor(type);
            } catch (NoSuchTypeException e) {
                throw new ObjectParseException(e.getMessage(), e);
            }
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
            if (transform.getSubobjectIdKey().isPresent()) {
                if (!typeDescr.getSubObjectType().isPresent()) {
                    //TODO CODE check this in parsing rules creation context if possible
                    throw new ObjectParseException("Subobject GUID transform should correspond " +
                            "to subobject type descriptor: " + rule);
                }
                subIds = toStringSet(
                        sourceKeywords.get(transform.getSubobjectIdKey().get()).values);
                if (guids.size() != 1) {
                    throw new ObjectParseException("In subobject IDs case source keyword " +
                            "should point to value with only one parent object reference");
                }
                GUID parentGuid = guids.iterator().next();
                guids = new LinkedHashSet<>();
                for (String subId : subIds) {
                    guids.add(new GUID(typeDescr.getStorageObjectType().getStorageCode(),
                            parentGuid.getAccessGroupId(),
                            parentGuid.getAccessGroupObjectId(), parentGuid.getVersion(), 
                            typeDescr.getSubObjectType().get(), subId));
                }
            }
            final Map<GUID, SearchObjectType> guidToType = lookup.getTypesForGuids(guids);
            for (final GUID guid : guids) {
                if (!guidToType.containsKey(guid)) {
                    throw new ObjectParseException("GUID " + guid + " not found");
                }
                final SearchObjectType actualType = guidToType.get(guid);
                if (!actualType.equals(type)) {
                    throw new ObjectParseException("GUID " + guid + " has unexpected type: " +
                            actualType);
                }
            }
            return guids.stream().map(GUID::toString).collect(Collectors.toList());
        case lookup:
            /* TODO CODE or DOCUMENTATION it appears that this only works if sourceKey = true and the sourceKey is a GUID transform. Check and document. */
            final String retProp = transform.getTargetKey().get();
            Set<String> guidText = toStringSet(value);
            Map<GUID, ObjectData> guidToObj = lookup.lookupObjectsByGuid(
                    guidText.stream().map(GUID::new).collect(Collectors.toSet()));
            List<Object> ret = new ArrayList<>();
            for (ObjectData obj : guidToObj.values()) {
                if (retProp.startsWith("key.")) {
                    String key = retProp.substring(4);
                    ret.add(obj.getKeyProperties().get(key));
                } else if (retProp.equals("oname")) {
                    ret.add(obj.getObjectName().get());
                }
            }
            return ret;
        default:
            // java whines unless this is here, but transform is guaranteed to be one of the above
            throw new RuntimeException("someone did something naughty");
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
            if (!rules.getPath().isPresent()) {
                throw new ObjectParseException("Path should be defined for non-derived " +
                        "indexing rules");
            }
            if (rules.isFromParent() != fromParent) {
                continue;
            }
            List<IndexingRules> rulesList = pathToRules.get(rules.getPath().get());
            if (rulesList == null) {
                rulesList = new ArrayList<>();
                pathToRules.put(rules.getPath().get(), rulesList);
            }
            rulesList.add(rules);
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
        public Map<GUID, SearchObjectType> getTypesForGuids(Set<GUID> guids)
                throws InterruptedException, IndexingException;
        public Map<GUID, ObjectData> lookupObjectsByGuid(Set<GUID> guids) 
                throws InterruptedException, IndexingException;
        public ObjectTypeParsingRules getTypeDescriptor(SearchObjectType type)
                throws IndexingException, NoSuchTypeException;
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
