package kbasesearchengine.search;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import com.google.common.base.Optional;

import com.google.common.collect.ImmutableMap;

import kbasesearchengine.common.GUID;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.parse.ParsedObject;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.SearchObjectType;
import kbasesearchengine.tools.Utils;

import us.kbase.common.service.Tuple2;
import us.kbase.common.service.UObject;

//TODO CODE remove 'fake' group IDs (-1 public, -2 admin). use alternate mechanism.

public class ElasticIndexingStorage implements IndexingStorage {

    private static final String OBJ_PROV_COMMIT_HASH = "prv_cmt";
    private static final String OBJ_PROV_MODULE_VERSION = "prv_ver";
    private static final String OBJ_PROV_METHOD = "prv_meth";
    private static final String OBJ_PROV_MODULE = "prv_mod";
    private static final String OBJ_MD5 = "md5";
    private static final String OBJ_COPIER = "copier";
    private static final String OBJ_CREATOR = "creator";
    private static final String OBJ_NAME = "oname";
    
    private static final String SEARCH_OBJ_TYPE = "otype";
    private static final String SEARCH_OBJ_TYPE_VER = "otypever";

    private HttpHost esHost;
    private String esUser;
    private String esPassword;
    private String indexNamePrefix;
    private boolean skipFullJson = false;
    private Map<SearchObjectType, String> typeVerToIndex = new LinkedHashMap<>();
    private Map<String, String> typeToIndex = new LinkedHashMap<>();
    private RestClient restClient = null;
    private File tempDir;
    
    public static final int PUBLIC_ACCESS_GROUP = -1;
    public static final int ADMIN_ACCESS_GROUP = -2;

    public ElasticIndexingStorage(HttpHost esHost, File tempDir) throws IOException {
        this.esHost = esHost;
        this.indexNamePrefix = "";
        this.tempDir = tempDir;
    }
    
    public HttpHost getEsHost() {
        return esHost;
    }
    
    public File getTempDir() {
        return tempDir;
    }
    
    public String getEsUser() {
        return esUser;
    }
    
    public void setEsUser(String esUser) {
        this.esUser = esUser;
    }
    
    public void setEsPassword(String esPassword) {
        this.esPassword = esPassword;
    }

    public String getIndexNamePrefix() {
        return indexNamePrefix;
    }
    
    public void setIndexNamePrefix(String indexNamePrefix) {
        this.indexNamePrefix = indexNamePrefix;
    }

    public boolean isSkipFullJson() {
        return skipFullJson;
    }
    
    public void setSkipFullJson(boolean skipFullJson) {
        this.skipFullJson = skipFullJson;
    }
    
    private String getAnyIndexPattern() {
        return indexNamePrefix + "*";
    }
    
    public void dropData() throws IOException {
        for (String indexName : listIndeces()) {
            if (indexName.startsWith(indexNamePrefix)) {
                deleteIndex(indexName);
            }
        }
    }
    
    /* checks that at least one index exists for a type, and throws an exception otherwise.
     * Returns a index string that matches any version of the type.
     */
    private String checkIndex(final String objectType) throws IOException {
        String ret = typeToIndex.get(objectType);
        if (ret == null) {
            ensureAtLeastOneIndexExists(objectType);
            ret = getAnyTypePattern(objectType);
            typeToIndex.put(objectType, ret);
        }
        return ret;
    }
    
    private void ensureAtLeastOneIndexExists(final String objectType) throws IOException {
        //TODO VERS need to check there aren't duplicate type names based on case
        final String prefix = (indexNamePrefix + objectType + "_").toLowerCase();
        for (final String index: listIndeces()) {
            if (index.startsWith(prefix)) {
                return;
            }
        }
        throw new IOException("No indexes exist for search type " + objectType);
    }

    private String getAnyTypePattern(final String objectType) {
        return (indexNamePrefix + objectType + "_*").toLowerCase();
    }

    /* checks that an index exists for a specific version of a type. If the index does not exist
     * and indexing rules are provided, creates the index, otherwise throws exception.
     * Returns the elastic search index name.
     */ 
    private String checkIndex(
            final SearchObjectType objectType,
            final List<IndexingRules> indexingRules)
            throws IOException {
        Utils.nonNull(objectType, "objectType");
        String ret = typeVerToIndex.get(objectType);
        if (ret == null) {
            ret = toIndexString(objectType);
            if (!listIndeces().contains(ret)) {
                if (indexingRules == null) {
                    throw new IOException("Index wasn't created for type " + objectType);
                }
                System.out.println("Creating Elasticsearch index: " + ret);
                createTables(ret, indexingRules);
            }
            typeVerToIndex.put(objectType, ret);
        }
        return ret;
    }

    private String toIndexString(final SearchObjectType objectType) {
        return (indexNamePrefix + objectType.getType() + "_" + objectType.getVersion())
                .toLowerCase();
    }
    
    // IO exceptions are thrown for failure on creating or writing to file or contacting ES.
    @Override
    public void indexObjects(
            final SearchObjectType objectType,
            final SourceData data,
            final Instant timestamp, 
            final String parentJsonValue,
            final GUID pguid,
            final Map<GUID, ParsedObject> idToObj,
            final boolean isPublic,
            final List<IndexingRules> indexingRules) 
            throws IOException {
        String indexName = checkIndex(objectType, indexingRules);
        for (GUID id : idToObj.keySet()) {
            GUID parentGuid = new GUID(id.getStorageCode(), id.getAccessGroupId(), 
                    id.getAccessGroupObjectId(), id.getVersion(), null, null);
            if (!parentGuid.equals(pguid)) {
                throw new IllegalStateException("Object GUID doesn't match parent GUID");
            }
        }
        File tempFile = File.createTempFile("es_bulk_", ".json", tempDir);
        try {
            PrintWriter pw = new PrintWriter(tempFile);
            int lastVersion = loadLastVersion(indexName, pguid, pguid.getVersion());
            Map<GUID, String> parentGuidToEsId = checkParentDoc(indexName, new LinkedHashSet<>(
                    Arrays.asList(pguid)), isPublic, lastVersion);
            if (idToObj.size() > 0) {
                Map<GUID, String> esIds = lookupDocIds(indexName, idToObj.keySet());
                for (GUID id : idToObj.keySet()) {
                    String esParentId = parentGuidToEsId.get(pguid);
                    ParsedObject obj = idToObj.get(id);
                    Map<String, Object> doc = convertObject(id, objectType, obj, data, 
                            timestamp, parentJsonValue, isPublic, lastVersion);
                    final Map<String, Object> index = new HashMap<>();
                    index.put("_index", indexName);
                    index.put("_type", getDataTableName());
                    index.put("parent", esParentId);
                    if (esIds.containsKey(id)) {
                        index.put("_id", esIds.get(id));
                    }
                    Map<String, Object> header = ImmutableMap.of("index", index);
                    pw.println(UObject.transformObjectToString(header));
                    pw.println(UObject.transformObjectToString(doc));
                }
                pw.close();
                makeBulkRequest("POST", indexName, tempFile);
                updateLastVersionsInData(indexName, pguid, lastVersion);
            }
        } finally {
            tempFile.delete();
        }
        refreshIndex(indexName);
    }
    
    private Map<String, Object> convertObject(
            final GUID id,
            final SearchObjectType objectType,
            final ParsedObject obj, 
            final SourceData data,
            final Instant timestamp,
            final String parentJson,
            final boolean isPublic,
            final int lastVersion) {
        Map<String, List<Object>> indexPart = new LinkedHashMap<>();
        for (String key : obj.keywords.keySet()) {
            indexPart.put(getKeyProperty(key), obj.keywords.get(key));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.putAll(indexPart);
        doc.put("guid", id.toString());
        doc.put(SEARCH_OBJ_TYPE, objectType.getType());
        doc.put(SEARCH_OBJ_TYPE_VER, objectType.getVersion());

        doc.put(OBJ_NAME, data.getName());
        doc.put(OBJ_CREATOR, data.getCreator());
        doc.put(OBJ_COPIER, data.getCopier().orNull());
        doc.put(OBJ_PROV_MODULE, data.getModule().orNull());
        doc.put(OBJ_PROV_METHOD, data.getMethod().orNull());
        doc.put(OBJ_PROV_MODULE_VERSION, data.getVersion().orNull());
        doc.put(OBJ_PROV_COMMIT_HASH, data.getCommitHash().orNull());
        doc.put(OBJ_MD5, data.getMD5().orNull());
        
        doc.put("timestamp", timestamp.toEpochMilli());
        doc.put("prefix", toGUIDPrefix(id));
        doc.put("str_cde", id.getStorageCode());
        doc.put("accgrp", id.getAccessGroupId());
        doc.put("version", id.getVersion());
        doc.put("islast", lastVersion == id.getVersion());
        doc.put("public", isPublic);
        doc.put("shared", false);
        if (!skipFullJson) {
            doc.put("ojson", obj.json);
            doc.put("pjson", parentJson);
        }
        return doc;
    }

    // this is only used in tests as of 12/12/17. remove and use indexObjects()?
    @Override
    public void indexObject(
            final GUID id,
            final SearchObjectType objectType,
            final ParsedObject obj,
            final SourceData data,
            final Instant timestamp,
            final String parentJsonValue,
            final boolean isPublic,
            final List<IndexingRules> indexingRules)
            throws IOException {
        String indexName = checkIndex(objectType, indexingRules);
        GUID parentGUID = new GUID(id.getStorageCode(), id.getAccessGroupId(), 
                id.getAccessGroupObjectId(), id.getVersion(), null, null);
        int lastVersion = loadLastVersion(indexName, id, id.getVersion());
        if (lastVersion != id.getVersion()) {
            System.out.println("ElasticSearchStorage.indexObject: unexpected versions: " +
                    lastVersion + " != " + id.getVersion());
        }
        String esParentId = checkParentDoc(indexName, new HashSet<>(Arrays.asList(parentGUID)),
                isPublic, lastVersion).get(parentGUID);
        Map<String, Object> doc = convertObject(id, objectType, obj, data, timestamp, 
                parentJsonValue, isPublic, lastVersion);
        String esId = lookupDocIds(indexName, new HashSet<>(Arrays.asList(id))).get(id);
        String requestUrl = "/" + indexName + "/" + getDataTableName() + "/";
        if (esId != null) {
            requestUrl += esId;
        }
        makeRequest("POST", requestUrl, doc, Arrays.asList(
                new Tuple2<String, String>().withE1("parent").withE2(esParentId)));
        updateLastVersionsInData(indexName, parentGUID, lastVersion);
        refreshIndex(indexName);
    }
    
    @Override
    public void flushIndexing(SearchObjectType objectType) throws IOException {
        refreshIndex(checkIndex(objectType, null));
    }
    
    private Map<GUID, String> lookupDocIds(String indexName, Set<GUID> guids) throws IOException {
        // terms = {"guid": [guids]}
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("guid", guids.stream().map(u -> u.toString()).collect(Collectors.toList()));

        // filter = {"terms": {"guid": [guids]}}
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("terms", terms);

        // bool = {"filter": [{"terms": {"guid": [guids]}}]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", Arrays.asList(filter));

        // query = {"bool": {"filter": [{"terms": {"guid": [guids]}}]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // doc = {"query": {"bool": {"filter": [{"terms": {"guid": [guids]}}]}}}
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);

        String urlPath = "/" + indexName + "/" + getDataTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        Map<GUID, String> ret = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            String id = (String)hit.get("_id");
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            GUID guid = new GUID((String) obj.get("guid"));
            ret.put(guid, id);
        }
        return ret;
    }

    private Map<GUID, String> lookupParentDocIds(String indexName, Set<GUID> guids) throws IOException {
        // terms = {"pguid": [guids]}
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("pguid", guids.stream().map(u -> u.toString()).collect(Collectors.toList()));

        // filter = {"terms": {"pguid": [guids]}}
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("terms", terms);

        // bool = {"filter": [{"terms": {"pguid": [guids]}}]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", Arrays.asList(filter));

        // query = {"bool": {"filter": [{"terms": {"pguid": [guids]}}]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // doc = {"query": {"bool": {"filter": [{"terms": {"pguid": [guids]}}]}}}
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);

        String urlPath = "/" + indexName + "/" + getAccessTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        Map<GUID, String> ret = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            String id = (String)hit.get("_id");
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            GUID guid = new GUID((String)obj.get("pguid"));
            ret.put(guid, id);
        }
        return ret;
    }

    public Map<String, Set<GUID>> groupParentIdsByIndex(Set<GUID> ids) throws IOException {
        Set<String> parentIds = new LinkedHashSet<>();
        for (GUID guid : ids) {
            parentIds.add(new GUID(guid.getStorageCode(), guid.getAccessGroupId(), 
                    guid.getAccessGroupObjectId(), guid.getVersion(), null, null).toString());
        }
        // terms = {"pguid": [ids]}
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("pguid", parentIds);

        // filter = {"terms: ": {"pguid": [ids]}}
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("terms", terms);

        // bool = {"filter": {"terms: ": {"pguid": [ids]}}}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filter);

        // query = {"bool": {"filter": {"terms: ": {"pguid": [ids]}}}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // doc = {"query": {"bool": {"filter": {"terms: ": {"pguid": [ids]}}}},
        //        "_source": ["pguid"]}
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("_source", Arrays.asList("pguid"));

        String urlPath = "/" + indexNamePrefix + "*/" + getAccessTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        Map<String, Set<GUID>> ret = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            String indexName = (String)hit.get("_index");
            Set<GUID> retSet = ret.get(indexName);
            if (retSet == null) {
                retSet = new LinkedHashSet<>();
                ret.put(indexName, retSet);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            GUID guid = new GUID((String)obj.get("pguid"));
            retSet.add(guid);
        }
        return ret;
    }

    private Map<String, Object> createFilter(String queryType, String keyName, Object value) {
        Map<String, Object> term = new LinkedHashMap<>();
        // term = {keyname: value}?
        if (keyName != null) {
            term.put(keyName, value);
        }

        Map<String, Object> termWrapper = new LinkedHashMap<>();
        termWrapper.put(queryType, term);
        // return = {queryType: {keyname: value}?}
        return termWrapper;
    }

    private Map<String, Object> createRangeFilter(String keyName, Object gte, Object lte) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (gte != null) {
            range.put("gte", gte);
        }
        if (lte != null) {
            range.put("lte", lte);
        }
        Map<String, Object> term = new LinkedHashMap<>();
        term.put(keyName, range);

        Map<String, Object> termWrapper = new LinkedHashMap<>();
        termWrapper.put("range", term);
        return termWrapper;
    }
    
    // throws IOexceptions for elastic connection issues & deserializaion issues
    @Override
    public Map<GUID, Boolean> checkParentGuidsExist(final Set<GUID> guids) throws IOException {
        Set<GUID> parentGUIDs = guids.stream().map(guid -> new GUID(
                guid.getStorageCode(),
                guid.getAccessGroupId(),
                guid.getAccessGroupObjectId(),
                guid.getVersion(),
                null, null))
                .collect(Collectors.toSet());
        final String indexName = getAnyIndexPattern();
        // In next operation map value may contain one of possible parents in case objectType==null
        final Map<GUID, String> map = lookupParentDocIds(indexName, parentGUIDs);
        return parentGUIDs.stream().collect(Collectors.toMap(Function.identity(), 
                guid -> map.containsKey(guid)));
    }

    private Integer loadLastVersion(String reqIndexName, GUID parentGUID, 
            Integer processedVersion) throws IOException {
        if (reqIndexName == null) {
            reqIndexName = getAnyIndexPattern();
        }
        String prefix = toGUIDPrefix(parentGUID);

        // term = {"prefix": prefix}
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("prefix", prefix);

        // filter = {"term": {"prefix": prefix}}
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("term", term);

        // bool = {"filter": [{"term": {"prefix": prefix}}]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", Arrays.asList(filter));

        // query = {"bool": {"filter": [{"term": {"prefix": prefix}}]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // doc = {"query": {"bool": {"filter": [{"term": {"prefix": prefix}}]}}}
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);

        String urlPath = "/" + reqIndexName + "/" + getAccessTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        Integer ret = null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            int version = (Integer)obj.get("version");
            if (ret == null || ret < version) {
                ret = version;
            }
        }
        if (processedVersion != null && (ret == null || ret < processedVersion)) {
            ret = processedVersion;
        }
        return ret;
    }
    
    private int updateLastVersionsInData(String indexName, GUID parentGUID,
            int lastVersion) throws IOException {
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        // {"prefix": prefix}
        String prefix = toGUIDPrefix(parentGUID);
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("prefix", prefix);

        // filter = {"term": {"prefix": prefix}}
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("term", term);

        // bool = {"filter": [{"term": {"prefix": prefix}}]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", Arrays.asList(filter));

        // query = {"bool": {"filter": [{"term": {"prefix": prefix}}]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // params = {"lastver": lastVersion}
        final Map<String, Object> params = new HashMap<>();
        params.put("lastver", lastVersion);

        // script = {"inline": "ctx._source.islast = (ctx._source.version == params.lastver)",
        //           "params": {"lastver": lastVersion}}
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", "ctx._source.islast = (ctx._source.version == params.lastver);");
        script.put("params", params);

        // doc = {"query": {"bool": {"filter": [{"term": {"prefix": prefix}}]}},
        //        "script": {"inline": "ctx._source.islast = (ctx._source.version == params.lastver)",
        //                   "params": {"lastver": lastVersion}}}
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getDataTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated");
    }

    private Map<GUID, String> checkParentDoc(String indexName, Set<GUID> parentGUIDs, 
            boolean isPublic, int lastVersion) throws IOException {
        boolean changed = false;
        Map<GUID, String> ret = lookupParentDocIds(indexName, parentGUIDs);
        for (GUID parentGUID : parentGUIDs) {
            if (ret.containsKey(parentGUID)) {
                continue;
            }
            String prefix = toGUIDPrefix(parentGUID);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("pguid", parentGUID.toString());
            doc.put("prefix", prefix);
            doc.put("version", parentGUID.getVersion());
            Set<Integer> accessGroupIds = new LinkedHashSet<>(Arrays.asList(
                    ADMIN_ACCESS_GROUP));
            if (parentGUID.getAccessGroupId() != null) {
                accessGroupIds.add(parentGUID.getAccessGroupId());
            }
            if (isPublic) {
                accessGroupIds.add(PUBLIC_ACCESS_GROUP);
            }
            Set<Integer> lastinGroupIds = parentGUID.getVersion() == lastVersion ? 
                    accessGroupIds : Collections.emptySet();
            doc.put("lastin", lastinGroupIds);
            doc.put("groups", accessGroupIds);
            doc.put("extpub", new ArrayList<Integer>());
            Response resp = makeRequest("POST", "/" + indexName + "/" + getAccessTableName() + "/", 
                    doc);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = UObject.getMapper().readValue(
                    resp.getEntity().getContent(), Map.class);
            ret.put(parentGUID, (String)data.get("_id"));
            changed = true;
            updateAccessGroupForVersions(indexName, parentGUID, lastVersion,
                    parentGUID.getAccessGroupId(), isPublic, true);
        }
        if (changed) {
            refreshIndex(indexName);
        }
        return ret;
    }
    
    private static final String UPDATE_ACC_GRP_VERS_TEMPLATE =
            "if (ctx._source.lastin.indexOf(params.%1$s) >= 0) {\n" +
            "  if (ctx._source.version != params.lastver) {\n" +
            "    ctx._source.lastin.remove(ctx._source.lastin.indexOf(params.%1$s));\n" +
            "    if (ctx._source.extpub.indexOf(params.%1$s) >= 0) {\n" + 
            "      ctx._source.extpub.remove(ctx._source.extpub.indexOf(params.%1$s));\n" +
            "    }\n" +
            "  }\n" +
            "} else {\n" +
            "  if (ctx._source.version == params.lastver) {\n" +
            "    ctx._source.lastin.add(params.%1$s);\n" +
            "    if (ctx._source.groups.indexOf(params.%1$s) < 0) {\n" +
            "      ctx._source.groups.add(params.%1$s);\n" +
            "    }\n" +
            "  }\n" +
            "}\n";
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    /* calling this method with accessGroupId == null and both booleans false is an error. */
    private boolean updateAccessGroupForVersions(
            String indexName,
            final GUID guid,
            final int lastVersion,
            final Integer accessGroupId,
            final boolean includePublicAccessID,
            final boolean includeAdminAccessID)
            throws IOException {
        /* this method will cause at most 6 script compilations, which seems like a lot...
         * Could make the script always the same and put in ifs but this should be ok for now.
         */
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        String prefix = toGUIDPrefix(guid);
        // bool = {"must": [{"term": {"prefix": prefix}?}]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", Arrays.asList(createFilter("term", "prefix", prefix)));

        // query = {"bool": {"must": [{"term": {"prefix": prefix}?}]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // params = {"lastver": lastVersion,
        //           ("accgrp": accessGroupId)?,
        //           ("pubaccgrp": -1)?,
        //           ("pubaccgrp": -2)?}
        StringBuilder inline = new StringBuilder();
        final Map<String, Object> params = new HashMap<>();
        params.put("lastver", lastVersion);
        if (accessGroupId != null) {
            inline.append(String.format(UPDATE_ACC_GRP_VERS_TEMPLATE, "accgrp"));
            params.put("accgrp", accessGroupId);
        }
        if (includePublicAccessID) {
            inline.append(String.format(UPDATE_ACC_GRP_VERS_TEMPLATE, "pubaccgrp"));
            params.put("pubaccgrp", PUBLIC_ACCESS_GROUP);
        }
        if (includeAdminAccessID) {
            inline.append(String.format(UPDATE_ACC_GRP_VERS_TEMPLATE, "adminaccgrp"));
            params.put("adminaccgrp", ADMIN_ACCESS_GROUP);
        }
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", inline.toString());
        script.put("params", params);

        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getAccessTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated") > 0;
    }

    private boolean removeAccessGroupForVersion(String indexName, GUID guid, 
            int accessGroupId) throws IOException {
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        // This flag shows that we work with other than physical access group this object exists in.
        boolean fromAllGroups = accessGroupId != guid.getAccessGroupId();
        String pguid = new GUID(guid.getStorageCode(), guid.getAccessGroupId(),
                guid.getAccessGroupObjectId(), guid.getVersion(), null, null).toString();
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", Arrays.asList(createFilter("term", "pguid", pguid),
                createFilter("term", "lastin", accessGroupId)));

        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("bool", bool);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("accgrp", accessGroupId);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", "" +
                "ctx._source.lastin.remove(ctx._source.lastin.indexOf(params.accgrp));\n" +
                "if (ctx._source.extpub.indexOf(params.accgrp) >= 0) {\n" +
                "  ctx._source.extpub.remove(ctx._source.extpub.indexOf(params.accgrp));\n" +
                "}\n" +
                (fromAllGroups ? (
                        "int pos = ctx._source.groups.indexOf(params.accgrp);\n" +
                                "if (pos >= 0) {\n" +
                                "  ctx._source.groups.remove(pos);\n" +
                                "}\n") : ""));
        script.put("params", params);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getAccessTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated") > 0;
    }

    private boolean updateBooleanFieldInData(String indexName, GUID parentGUID,
            String field, boolean value) throws IOException {
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        String prefix = toGUIDPrefix(parentGUID);
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", Arrays.asList(createFilter("term", "prefix", prefix),
                createFilter("term", "version", parentGUID.getVersion())));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        final Map<String, Object> params = new HashMap<>();
        params.put("field", field);
        params.put("value", value);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", "ctx._source[params.field] = params.value;");
        script.put("params", params);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getDataTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated") > 0;
    }

    private String toGUIDPrefix(GUID parentGUID) {
        return new GUID(parentGUID.getStorageCode(), parentGUID.getAccessGroupId(),
                parentGUID.getAccessGroupObjectId(), null, null, null).toString();
    }
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public int setNameOnAllObjectVersions(final GUID object, final String newName)
            throws IOException {
        return setFieldOnObject(object, OBJ_NAME, newName, true);
    }
    
    /* expects that GUID does not have sub object info */
    // TODO CODE allow providing index name for optimization
    private int setFieldOnObject(
            final GUID object,
            final String field,
            final Object value,
            final boolean allVersions)
            throws IOException {
        final String index = getAnyIndexPattern();
        final Map<String, Object> query;
        if (allVersions) {
            query = createFilter("term", "prefix", toGUIDPrefix(object));
        } else {
            query = createFilter("term", "guid", object.toString());
        }
        final Map<String, Object> script = ImmutableMap.of(
                "inline", "ctx._source[params.field] = params.value",
                "params", ImmutableMap.of("field", field, "value", value));
        final Map<String, Object> doc = ImmutableMap.of(
                "query", query,
                "script", script);
        final String urlPath = "/" + index + "/" + getDataTableName() + "/_update_by_query";
        final Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        final Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (int) data.get("updated");
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void shareObjects(Set<GUID> guids, int accessGroupId, 
            boolean isExternalPublicGroup) throws IOException {
        Map<String, Set<GUID>> indexToGuids = groupParentIdsByIndex(guids);
        for (String indexName : indexToGuids.keySet()) {
            Set<GUID> toAddExtPub = new LinkedHashSet<GUID>();
            boolean needRefresh = false;
            for (GUID guid : indexToGuids.get(indexName)) {
                if (updateAccessGroupForVersions(indexName, guid, guid.getVersion(), accessGroupId,
                        false, false)) {
                    needRefresh = true;
                }
                if (accessGroupId == PUBLIC_ACCESS_GROUP) {
                    if (updateBooleanFieldInData(indexName, guid, "public", true)) {
                        needRefresh = true;
                    }
                } else if (accessGroupId != guid.getAccessGroupId()) {
                    if (updateBooleanFieldInData(indexName, guid, "shared", true)) {
                        needRefresh = true;
                    }
                    if (isExternalPublicGroup) {
                        toAddExtPub.add(guid);
                    }
                }
            }
            if (needRefresh) {
                refreshIndex(indexName);
            }
            if (!toAddExtPub.isEmpty()) {
                needRefresh = false;
                for (GUID guid : indexToGuids.get(indexName)) {
                    if (addExtPubForVersion(indexName, guid, accessGroupId)) {
                        needRefresh = true;
                    }
                }
                if (needRefresh) {
                    refreshIndex(indexName);
                }
            }
        }
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void unshareObjects(Set<GUID> guids, int accessGroupId) throws IOException {
        Map<String, Set<GUID>> indexToGuids = groupParentIdsByIndex(guids);
        for (String indexName : indexToGuids.keySet()) {
            boolean needRefresh = false;
            for (GUID guid : indexToGuids.get(indexName)) {
                if (removeAccessGroupForVersion(indexName, guid, accessGroupId)) {
                    needRefresh = true;
                }
                if (accessGroupId == PUBLIC_ACCESS_GROUP) {
                    if (updateBooleanFieldInData(indexName, guid, "public", false)) {
                        needRefresh = true;
                    }
                }
                //TODO NOW how is share bit unset?
            }
            if (needRefresh) {
                refreshIndex(indexName);
            }
        }
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void deleteAllVersions(final GUID guid) throws IOException {
        // could optimize later by making LLV return the index name
        final Integer ver = loadLastVersion(null, guid, null);
        if (ver == null) {
            //TODO NOW throw exception? means a delete event occurred when there were no objects
            return;
        }
        final String indexName = getAnyIndexPattern();
        setFieldOnObject(withVersion(guid, ver), "islast", false, false);
        // -3 is a hack to always remove access groups
        updateAccessGroupForVersions(indexName, guid, -3, guid.getAccessGroupId(), false, false);
        /* changing the public field doesn't make a ton of sense - the object is still in a public
         * workspace. 
         * TODO NOW add a deleted flag, use that instead.
         */
//        setFieldOnObjectForAllVersions(guid, "public", false);
        //TODO NOW this doesn't handle removing public (-1) from the access doc because it can't know that's the right thing to do
        //TODO NOW admin access group id has same problem as public access group id
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void undeleteAllVersions(final GUID guid) throws IOException {
        // could optimize later by making LLV return the index name
        final Integer ver = loadLastVersion(null, guid, null);
        if (ver == null) {
            //TODO NOW throw exception? means an undelete event occurred when there were no objects
            return;
        }
        updateLastVersionsInData(null, guid, ver);
        updateAccessGroupForVersions(null, guid, ver, guid.getAccessGroupId(), false, true);
        // TODO NOW remove deleted flag from delete all versions
        
    }
    
    private GUID withVersion(final GUID guid, int ver) {
        return new GUID(guid.getStorageCode(), guid.getAccessGroupId(),
                guid.getAccessGroupObjectId(), ver, null, null);
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void publishObjects(Set<GUID> guids) throws IOException {
        shareObjects(guids, PUBLIC_ACCESS_GROUP, false);
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void unpublishObjects(Set<GUID> guids) throws IOException {
        unshareObjects(guids, PUBLIC_ACCESS_GROUP);
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void publishAllVersions(final GUID guid) throws IOException {
        setFieldOnObject(guid, "public", true, true);
    }
    
    //IO exception thrown for deserialization & elasticsearch contact errors
    @Override
    public void unpublishAllVersions(final GUID guid) throws IOException {
        setFieldOnObject(guid, "public", false, true);
    }

    private boolean addExtPubForVersion(String indexName, GUID guid, 
            int accessGroupId) throws IOException {
        // Check that we work with other than physical access group this object exists in.
        if (accessGroupId == guid.getAccessGroupId()) {
            throw new IllegalStateException("Access group should be external");
        }
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        String pguid = new GUID(guid.getStorageCode(), guid.getAccessGroupId(),
                guid.getAccessGroupObjectId(), guid.getVersion(), null, null).toString();
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", Arrays.asList(createFilter("term", "pguid", pguid)));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("accgrp", accessGroupId);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", "" +
                "if (ctx._source.extpub.indexOf(params.accgrp) < 0) {\n" +
                "  ctx._source.extpub.add(params.accgrp);\n" +
                "}\n");
        script.put("params", params);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getAccessTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated") > 0;
    }

    @Override
    public void publishObjectsExternally(Set<GUID> guids, int accessGroupId) throws IOException {
        Map<String, Set<GUID>> indexToGuids = groupParentIdsByIndex(guids);
        for (String indexName : indexToGuids.keySet()) {
            boolean needRefresh = false;
            for (GUID guid : indexToGuids.get(indexName)) {
                if (addExtPubForVersion(indexName, guid, accessGroupId)) {
                    needRefresh = true;
                }
            }
            if (needRefresh) {
                refreshIndex(indexName);
            }
        }
    }

    private boolean removeExtPubForVersion(String indexName, GUID guid, 
            int accessGroupId) throws IOException {
        // Check that we work with other than physical access group this object exists in.
        if (accessGroupId == guid.getAccessGroupId()) {
            throw new IllegalStateException("Access group should be external");
        }
        if (indexName == null) {
            indexName = getAnyIndexPattern();
        }
        String pguid = new GUID(guid.getStorageCode(), guid.getAccessGroupId(),
                guid.getAccessGroupObjectId(), guid.getVersion(), null, null).toString();
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", Arrays.asList(createFilter("term", "pguid", pguid),
                createFilter("term", "extpub", accessGroupId)));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("accgrp", accessGroupId);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("inline", "" +
                "ctx._source.extpub.remove(ctx._source.extpub.indexOf(params.accgrp));\n");
        script.put("params", params);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("script", script);

        String urlPath = "/" + indexName + "/" + getAccessTableName() + "/_update_by_query";
        Response resp = makeRequest("POST", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        return (Integer)data.get("updated") > 0;
    }

    @Override
    public void unpublishObjectsExternally(Set<GUID> guids, int accessGroupId) throws IOException {
        Map<String, Set<GUID>> indexToGuids = groupParentIdsByIndex(guids);
        for (String indexName : indexToGuids.keySet()) {
            boolean needRefresh = false;
            for (GUID guid : indexToGuids.get(indexName)) {
                if (removeExtPubForVersion(indexName, guid, accessGroupId)) {
                    needRefresh = true;
                }
            }
            if (needRefresh) {
                refreshIndex(indexName);
            }
        }
    }
    
    @Override
    public List<ObjectData> getObjectsByIds(Set<GUID> ids) throws IOException {
        PostProcessing pp = new PostProcessing();
        pp.objectInfo = true;
        pp.objectData = true;
        pp.objectKeys = true;
        return getObjectsByIds(ids, pp);
    }
    
    @Override
    public List<ObjectData> getObjectsByIds(Set<GUID> ids, PostProcessing pp) 
            throws IOException {
<<<<<<< HEAD
        Map<String, Object> terms = new LinkedHashMap<String, Object>() {{
            put("guid", ids.stream().map(u -> u.toString()).collect(Collectors.toList()));
        }};
        Map<String, Object> filter = new LinkedHashMap<String, Object>() {{
            put("terms", terms);
        }};
        Map<String, Object> bool = new LinkedHashMap<String, Object>() {{
            put("filter", filter);
        }};
        Map<String, Object> query = new LinkedHashMap<String, Object>() {{
            put("bool", bool);

        }};
        Map<String, Object> doc = new LinkedHashMap<String, Object>() {{
            put("query", query);
            //put("_source", Arrays.asList("ojson"));
        }};
=======
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("guid", ids.stream().map(u -> u.toString()).collect(Collectors.toList()));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("terms", terms);

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filter);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        //doc.put("_source", Arrays.asList("ojson"));
>>>>>>> 4a089dc8e3a921e660680aac2e94bb934f6b163e

        String urlPath = "/" + indexNamePrefix + "*/" + getDataTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        List<ObjectData> ret = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            ObjectData item = buildObjectData(obj, pp.objectInfo, pp.objectKeys, 
                    pp.objectData, pp.objectDataIncludes);
            ret.add(item);
        }
        return ret;
    }

    private ObjectData buildObjectData(Map<String, Object> obj, boolean info, boolean keys, 
            boolean json, List<String> objectDataIncludes) {
        // TODO: support sub-data selection based on objectDataIncludes (acts on parent json or sub object json)
        ObjectData item = new ObjectData();
        item.guid = new GUID((String)obj.get("guid"));
        if (info) {
            item.objectName = (String)obj.get(OBJ_NAME);
            item.creator = (String) obj.get(OBJ_CREATOR);
            item.copier = (String) obj.get(OBJ_COPIER);
            item.module = (String) obj.get(OBJ_PROV_MODULE);
            item.method = (String) obj.get(OBJ_PROV_METHOD);
            item.moduleVersion = (String) obj.get(OBJ_PROV_MODULE_VERSION);
            item.commitHash = (String) obj.get(OBJ_PROV_COMMIT_HASH);
            item.md5 = (String) obj.get(OBJ_MD5);
            item.type = new SearchObjectType(
                    (String) obj.get(SEARCH_OBJ_TYPE),
                    (Integer) obj.get(SEARCH_OBJ_TYPE_VER));
            Object dateProp = obj.get("timestamp");
            item.timestamp = (dateProp instanceof Long) ? (Long)dateProp : 
                Long.parseLong(String.valueOf(dateProp));
        }
        if (json) {
            item.data = UObject.transformStringToObject((String)obj.get("ojson"), Object.class);
            String pjson = (String)obj.get("pjson");
            if (pjson != null) {
                item.parentData = UObject.transformStringToObject(pjson, Object.class);
                item.parentGuid = new GUID(item.guid.getStorageCode(), item.guid.getAccessGroupId(),
                        item.guid.getAccessGroupObjectId(), item.guid.getVersion(), null, null);
            }
        }
        if (keys) {
            Map<String, String> keyProps = new LinkedHashMap<>();
            for (String key : obj.keySet()) {
                if (key.startsWith("key.")) {
                    Object objValue = obj.get(key);
                    String textValue = null;
                    if (objValue instanceof List) {
                        @SuppressWarnings("unchecked")
                        final List<Object> objValue2 = (List<Object>) objValue;
                        textValue = objValue2.stream().map(Object::toString)
                                .collect(Collectors.joining(", "));
                    } else {
                        textValue = String.valueOf(objValue);
                    }
                    keyProps.put(key.substring(4), textValue);
                }
            }
            item.keyProps = keyProps;
        }
        return item;
    }
<<<<<<< HEAD

    @SuppressWarnings("serial")
=======
    
>>>>>>> 4a089dc8e3a921e660680aac2e94bb934f6b163e
    private Map<String, Object> createPublicShouldBlock(boolean withAllHistory) {
        List<Object> must0List = new ArrayList<>();
        must0List.add(createFilter("term", "public", true));
        if (!withAllHistory) {
            must0List.add(createFilter("term", "islast", true));
        }
        Map<String, Object> bool0 = new LinkedHashMap<>();
        bool0.put("must", must0List);

        LinkedHashMap<String, Object> bool0Wrapper = new LinkedHashMap<>();
        bool0Wrapper.put("bool", bool0);
        return bool0Wrapper;
    }
    
    private Map<String, Object> createOwnerShouldBlock(AccessFilter accessFilter) {
        List<Object> must1List = new ArrayList<>();
        if (!accessFilter.isAdmin) {
            Set<Integer> accGroups = accessFilter.accessGroupIds;
            if (accGroups == null) {
                accGroups = Collections.emptySet();
            }
            must1List.add(createFilter("terms", "accgrp", accGroups));
        }
        if (!accessFilter.withAllHistory) {
            must1List.add(createFilter("term", "islast", true));
        }
        Map<String, Object> bool1 = new LinkedHashMap<>();
        bool1.put("must", must1List);

        LinkedHashMap<String, Object> bool1Wrapper = new LinkedHashMap<>();
        bool1Wrapper.put("bool", bool1);

        return bool1Wrapper;
    }
    
    private Map<String, Object> createSharedShouldBlock(Map<String, Object> mustForShared) {
        List<Object> must2List = new ArrayList<>(Arrays.asList(
                createFilter("term", "shared", true), mustForShared));
        Map<String, Object> bool2 = new LinkedHashMap<>();
        bool2.put("must", must2List);

        LinkedHashMap<String, Object> bool2Wrapper = new LinkedHashMap<>();
        bool2Wrapper.put("bool", bool2);
        return bool2Wrapper;
    }
    
    //TODO VERS should this return SearchObjectType -> Integer map? Maybe an option to combine versions
    @Override
    public Map<String, Integer> searchTypes(MatchFilter matchFilter,
            AccessFilter accessFilter) throws IOException {
        Map<String, Object> mustForShared = createAccessMustBlock(accessFilter);
        if (mustForShared == null) {
            return Collections.emptyMap();
        }
        List<Object> shouldList = new ArrayList<>();
        // TODO: support for matchFilter.accessGroupId (e.g. reduce search scope to one group)
        List<Object> matchFilters = new ArrayList<>(prepareMatchFilters(matchFilter));
        // Public block (we exclude it for admin because it's covered by owner block)
        if (accessFilter.withPublic && !accessFilter.isAdmin) {
            shouldList.add(createPublicShouldBlock(accessFilter.withAllHistory));
        }

        // Owner block
        shouldList.add(createOwnerShouldBlock(accessFilter));

        // Shared block
        shouldList.add(createSharedShouldBlock(mustForShared));
        // Rest of query
<<<<<<< HEAD
        Map<String, Object> filterBool = new LinkedHashMap<String, Object>() {{
            put("should", shouldList);
        }};
        Map<String, Object> filter = new LinkedHashMap<String, Object>() {{
            put("bool", filterBool);

        }};
        Map<String, Object> bool = new LinkedHashMap<String, Object>() {{
            put("must", matchFilters);
            put("filter", Arrays.asList(filter));


        }};
        Map<String, Object> query = new LinkedHashMap<String, Object>() {{
            put("bool", bool);
        }};
        Map<String, Object> terms = new LinkedHashMap<String, Object>() {{
            put("field", SEARCH_OBJ_TYPE);
        }};
=======
        Map<String, Object> filterBool = new LinkedHashMap<>() ;
        filterBool.put("should", shouldList);

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("bool", filterBool);

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", matchFilters);
        bool.put("filter", Arrays.asList(filter));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("field", SEARCH_OBJ_TYPE);

>>>>>>> 4a089dc8e3a921e660680aac2e94bb934f6b163e
        //TODO VERS if this aggregates by type version, need to add the version field to the terms
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("terms", terms);

        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put("types", agg);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("aggregations", aggs);
        doc.put("size", 0);

        String urlPath = "/" + indexNamePrefix + "*/" + getDataTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> aggMap = (Map<String, Object>) data.get("aggregations");
        @SuppressWarnings("unchecked")
        Map<String, Object> typeMap = (Map<String, Object>) aggMap.get("types");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) typeMap.get("buckets");
        Map<String, Integer> ret = new TreeMap<>();
        for (Map<String, Object> bucket : buckets) {
            String objType = (String)bucket.get("key");
            Integer count = (Integer)bucket.get("doc_count");
            ret.put(objType, count);
        }
        return ret;
    }
    
    @Override
    public FoundHits searchIds(String objectType, MatchFilter matchFilter, 
            List<SortingRule> sorting, AccessFilter accessFilter, Pagination pagination) 
                    throws IOException {
        return queryHits(objectType, prepareMatchFilters(matchFilter),
                sorting, accessFilter, pagination, null);
    }

    @Override
    public FoundHits searchObjects(String objectType, MatchFilter matchFilter,
            List<SortingRule> sorting, AccessFilter accessFilter,
            Pagination pagination, PostProcessing postProcessing)
            throws IOException {
        return queryHits(objectType, prepareMatchFilters(matchFilter),
                sorting, accessFilter, pagination, postProcessing);
    }
    
    public Set<GUID> searchIds(String objectType, MatchFilter matchFilter, 
            List<SortingRule> sorting, AccessFilter accessFilter) throws IOException {
        return searchIds(objectType, matchFilter, sorting, accessFilter, null).guids;
    }

    private List<Map<String, Object>> prepareMatchFilters(MatchFilter matchFilter) {
        List<Map<String, Object>> ret = new ArrayList<>();
        if (matchFilter.fullTextInAll != null) {
            LinkedHashMap<String, Object> query = new LinkedHashMap<String, Object>() {{
                put("query", matchFilter.fullTextInAll);
                put("operator", "and");
            }};
            LinkedHashMap<String, Object> allQuery = new LinkedHashMap<String, Object>() {{
                put("_all", query);
            }};
            LinkedHashMap<String, Object> match = new LinkedHashMap<String, Object>() {{
                put("match",allQuery);
            }};
            ret.add(match);
        }
        /*if (matchFilter.accessGroupId != null) {
            ret.add(createAccessMustBlock(new LinkedHashSet<>(Arrays.asList(
                    matchFilter.accessGroupId)), withAllHistory));
        }*/
        if (matchFilter.objectName != null) {
            ret.add(createFilter("match", OBJ_NAME, matchFilter.fullTextInAll));
        }
        if (matchFilter.lookupInKeys != null) {
            for (String keyName : matchFilter.lookupInKeys.keySet()) {
                MatchValue value = matchFilter.lookupInKeys.get(keyName);
                String keyProp = getKeyProperty(keyName);
                if (value.value != null) {
                    ret.add(createFilter("term", keyProp, value.value));
                } else if (value.minInt != null || value.maxInt != null) {
                    ret.add(createRangeFilter(keyProp, value.minInt, value.maxInt));
                } else if (value.minDate != null || value.maxDate != null) {
                    ret.add(createRangeFilter(keyProp, value.minDate, value.maxDate));
                } else if (value.minDouble != null || value.maxDouble != null) {
                    ret.add(createRangeFilter(keyProp, value.minDouble, value.maxDouble));
                }
            }
        }
        if (matchFilter.timestamp != null) {
            ret.add(createRangeFilter("timestamp", matchFilter.timestamp.minDate, 
                    matchFilter.timestamp.maxDate));
        }
        // TODO: support parent guid (reduce search scope to one object, e.g. features of one geneom)
        if (ret.isEmpty()) {
            ret.add(createFilter("match_all", null, null));
        }
        return ret;
    }
    
    private Map<String, Object> createAccessMustBlock(AccessFilter accessFilter) {
        Set<Integer> accessGroupIds = new LinkedHashSet<>();
        if (accessFilter.isAdmin) {
            accessGroupIds.add(ADMIN_ACCESS_GROUP);
        } else {
            if (accessFilter.accessGroupIds != null) {
                accessGroupIds.addAll(accessFilter.accessGroupIds);
            }
            if (accessFilter.withPublic) {
                accessGroupIds.add(PUBLIC_ACCESS_GROUP);
            }
        }
        if (accessGroupIds.isEmpty()) {
            return null;
        }
        return createAccessMustBlock(accessGroupIds, accessFilter.withAllHistory,
                accessFilter.withPublic);
    }
    
    private Map<String, Object> createAccessMustBlock(Set<Integer> accessGroupIds, 
            boolean withAllHistory, boolean withPublic) {
        // should = []
        List<Object> should = new ArrayList<>();

        // match = {groupListProp: [accessGroupIds]}
        String groupListProp = withAllHistory ? "groups" : "lastin";  // I think lastin means last version even though version is orthogonal to the concept of groups?
        Map<String, Object> match = new LinkedHashMap<>();
        match.put(groupListProp, accessGroupIds);

        // terms = {"terms": { groupListProp: [accessGroupIds]}}
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("terms", match);


        // should = [{"terms": {groupListProp: [accessGroupIds]}}]
        should.add(terms);

        if (withPublic) {
            // Case of public workspaces containing DataPalette referencing to given object
            // We basically check how many public workspaces (external comparing to home 
            // workspace of given object) have DataPalettes referencing given object (and 
            // version). If this number is 0 then object+version is not visible as public 
            // through DataPalettes. If it's >0 (which is the same as existence of keywords 
            // in 'extpub') then it's visible.
            // exists = {"field", "extpub"}
            Map<String, Object> exists = new LinkedHashMap<>();
            exists.put("field", "extpub");

            // existsWrapper = {"exists": {"field", "extpub"}}
            Map<String, Object> existwrapper = new LinkedHashMap<>();
            existwrapper.put("exists", exists);

            // should = [{"terms": {groupListProp: [accessGroupIds]}}
            //           {"exists": {"field", "extpub"}}]
            should.add(existwrapper);
        }
        // bool = {"should": [{"terms": {groupListProp: [accessGroupIds]}}
        //                    {"exists": {"field", "extpub"}}?]}
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", should);

        // query = {"bool": {"should": [{"terms": {groupListProp: [accessGroupIds]}}
        //                  {"exists": {"field", "extpub"}}?]}}
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        // hasParent = {"parent_type": "access",
        //              "query": {"bool": {"should": [{"terms": {groupListProp: [accessGroupIds]}}
        //                                {"exists": {"field", "extpub"}}?]}}}
        Map<String, Object> hasParent = new LinkedHashMap<>();
        hasParent.put("parent_type", getAccessTableName());
        hasParent.put("query", query);

        // hasParentWrapper = {"hasParent": {"parent_type": "access",
        //                                   "query": {"bool": {"should": [{"terms": {groupListProp: [accessGroupIds]}}
        //                                                     {"exists": {"field", "extpub"}}?]}}}}
        Map<String, Object> hasParentWrapper = new LinkedHashMap<>();
        hasParentWrapper.put("has_parent", hasParent);
        return hasParentWrapper;
    }
    
    private FoundHits queryHits(
            final String objectType,
            final List<Map<String, Object>> matchFilters, 
            List<SortingRule> sorting,
            final AccessFilter accessFilter,
            final Pagination pg,
            final PostProcessing pp)
            throws IOException {
        // initialize args
        int pgStart = pg == null || pg.start == null ? 0 : pg.start;
        int pgCount = pg == null || pg.count == null ? 50 : pg.count;
        Pagination pagination = new Pagination(pgStart, pgCount);
        if (sorting == null || sorting.isEmpty()) {
            SortingRule sr = new SortingRule();
            sr.isTimestamp = true;
            sr.ascending = true;
            sorting = Arrays.asList(sr);
        }
        FoundHits ret = new FoundHits();
        ret.pagination = pagination;
        ret.sortingRules = sorting;

        Map<String, Object> mustForShared = createAccessMustBlock(accessFilter);
        if (mustForShared == null) {
            ret.total = 0;
            ret.guids = Collections.emptySet();
            return ret;
        }
        List<Object> shouldList = new ArrayList<>();
        // TODO: support for matchFilter.accessGroupId  (e.g. reduce search scope to one group)
        // Public block (we exclude it for admin because it's covered by owner block)
        if (accessFilter.withPublic && !accessFilter.isAdmin) {
            shouldList.add(createPublicShouldBlock(accessFilter.withAllHistory));
        }
        // Owner block
        shouldList.add(createOwnerShouldBlock(accessFilter));
        // Shared block
        shouldList.add(createSharedShouldBlock(mustForShared));
        // Rest of query
        Map<String, Object> filterBool = new LinkedHashMap<>();
        filterBool.put("should", shouldList);

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("bool", filterBool);

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", matchFilters);
        bool.put("filter", Arrays.asList(filter));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bool", bool);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("from", pagination.start);
        doc.put("size", pagination.count);

        boolean loadObjects = pp != null && (pp.objectInfo || pp.objectData || pp.objectKeys);
        if (!loadObjects) {
            doc.put("_source", Arrays.asList("guid"));
        }
        List<Object> sort = new ArrayList<>();
        doc.put("sort", sort);
        for (SortingRule sr : sorting) {
            String keyProp = sr.isTimestamp ? "timestamp" : (sr.isObjectName ? OBJ_NAME : 
                getKeyProperty(sr.keyName));
            Map<String, Object> sortOrder = new LinkedHashMap<>();
            sortOrder.put("order", sr.ascending ? "asc" : "desc");

            Map<String, Object> sortOrderWrapper = new LinkedHashMap<>();
            sortOrderWrapper.put(keyProp, sortOrder);
            sort.add(sortOrderWrapper);
        }
        String indexName = objectType == null ? getAnyIndexPattern() : checkIndex(objectType);
        String urlPath = "/" + indexName + "/" + getDataTableName() + "/_search";
        Response resp = makeRequest("GET", urlPath, doc);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                resp.getEntity().getContent(), Map.class);
        ret.guids = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> hitMap = (Map<String, Object>) data.get("hits");
        ret.total = (Integer)hitMap.get("total");
        if (loadObjects) {
            ret.objects = new ArrayList<ObjectData>();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitMap.get("hits");
        for (Map<String, Object> hit : hitList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) hit.get("_source");
            String guidText = (String)obj.get("guid");
            ret.guids.add(new GUID(guidText));
            if (loadObjects) {
                ret.objects.add(buildObjectData(obj, pp.objectInfo, pp.objectKeys, 
                    pp.objectData, pp.objectDataIncludes));
            }
        }
        return ret;
    }
    
    private String getKeyProperty(String keyName) {
        return "key." + keyName;
    }

    public Set<String> listIndeces() throws IOException {
        Set<String> ret = new TreeSet<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = UObject.getMapper().readValue(
                makeRequest("GET", "/_aliases", null).getEntity().getContent(), Map.class);
        ret.addAll(data.keySet());
        return ret;
    }
    
    public Response deleteIndex(String indexName) throws IOException {
        return makeRequest("DELETE", "/" + indexName, null);
    }
    
    public Response refreshIndex(String indexName) throws IOException {
        return makeRequest("POST", "/" + indexName + "/_refresh", null);
    }
    
    /** Refresh the elasticsearch index, where the index prefix is set by
     * {@link #setIndexNamePrefix(String)}. Primarily used for testing.
     * @param typeName the name of the type.
     * @return the response from the ElasticSearch server.
     * @throws IOException if an IO error occurs.
     */
    public Response refreshIndexByType(final SearchObjectType typeName)
            throws IOException {
        return refreshIndex(toIndexString(typeName));
    }

    public Response makeRequest(String reqType, String urlPath, Map<String, ?> doc) 
            throws IOException {
        return makeRequest(reqType, urlPath, doc, Collections.<String, String>emptyMap());
    }

    private Response makeRequest(String reqType, String urlPath, Map<String, ?> doc, 
            List<Tuple2<String, String>> attributes) throws IOException {
        return makeRequest(reqType, urlPath, doc, attributes.stream().collect(
                Collectors.toMap(u -> u.getE1(), u -> u.getE2())));
    }
    
    private RestClient getRestClient() {
        if (restClient == null) {
            RestClientBuilder restClientBld = RestClient.builder(esHost);
            restClientBld.setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
                @Override
                public RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
                    return requestConfigBuilder.setConnectTimeout(10000)
                            .setSocketTimeout(120000);
                }
            }).setMaxRetryTimeoutMillis(120000);
            List<Header> headers = new ArrayList<>();
            headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));
            //headers.add(new BasicHeader("Role", "Read"));
            restClientBld.setDefaultHeaders(headers.toArray(new Header[headers.size()]));
            if (esUser != null) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY, 
                        new UsernamePasswordCredentials(esUser, esPassword));
                restClientBld.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder hacb) {
                        return hacb.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });
            }
            restClient = restClientBld.build();
        }
        return restClient;
    }
    
    public Response makeBulkRequest(String reqType, String indexName, File jsonData) 
            throws IOException {
        RestClient restClient = getRestClient();
        try (InputStream is = new FileInputStream(jsonData)) {
            InputStreamEntity body = new InputStreamEntity(is);
            Response response = restClient.performRequest(reqType, "/" + indexName + "/_bulk", 
                    Collections.emptyMap(), body);
            return response;
        }
    }
    
    public Response makeRequest(String reqType, String urlPath, Map<String, ?> doc, 
            Map<String, String> attributes) throws IOException {
        try {
            HttpEntity body = doc == null ? null : stringEntity(
                UObject.transformObjectToString(doc));
            RestClient restClient = getRestClient();  //restClientBld.build();
            return restClient.performRequest(reqType, urlPath, attributes, body);
        } catch (ResponseException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }
    
    private StringEntity stringEntity(final String string) {
        try {
            return new StringEntity(string);
            /* based on reading the code here:
             * https://svn.apache.org/repos/asf/httpcomponents/httpcore/branches/4.4.x/httpcore/src/main/java/org/apache/http/entity/StringEntity.java
             * this SE constructor never actually throws UEE. It looks like a typo. Hence ignore.
             */
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(
                    "This error can't happen, so you're obviously not seeing it.", e);
        }
    }

    private String getEsType(boolean fullText, Optional<String> keywordType) {
        if (fullText) {
            return "text";
        }
        if (keywordType.get().equals("string")) {
            return "keyword"; //TODO CODE why? why? why? 
        }
        return keywordType.get();
    }
    
    private String getDataTableName() {
        return "data";
    }
    
    private String getAccessTableName() {
        return "access";
    }
    
    private void createAccessTable(Map<String, Object> mappings) {
        String tableName = getAccessTableName();
        // mappings = {"access": {}}
        Map<String, Object> table = new LinkedHashMap<>();
        mappings.put(tableName, table);

        // props = {"properties": {}}
        Map<String, Object> props = new LinkedHashMap<>();
        table.put("properties", props);

        // props = {"properties": {}, "pguid": {"type": "keyword"}}
        Map<String, Object> tmp = new LinkedHashMap<>();
        tmp.put("type", "keyword");
        props.put("pguid", tmp);

        // props = {"properties": {},
        //          "pguid": {"type": "keyword"},
        //          "prefix": {"type": "keyword"}}
        tmp = new LinkedHashMap<>();
        tmp.put("type", "keyword");
        props.put("prefix", tmp);

        // props = {"properties": {},
        //          "pguid": {"type": "keyword"},
        //          "prefix": {"type": "keyword"},
        //          "version": {"type": "integer"}}
        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("version", tmp);

        // props = {"properties": {},
        //          "pguid": {"type": "keyword"},
        //          "prefix": {"type": "keyword"},
        //          "version": {"type": "integer"},
        //          "lastin": {"type": "integer"}}
        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("lastin", tmp);

        // props = {"properties": {},
        //          "pguid": {"type": "keyword"},
        //          "prefix": {"type": "keyword"},
        //          "version": {"type": "integer"},
        //          "lastin": {"type": "integer"},
        //          "groups": {"type": "integer"}}
        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("groups", tmp);

        // List of external workspaces containing DataPalette pointing to this object
        // This is the way to check how many public workspaces (external comparing to 
        // home workspace of an object) have DataPalettes referencing given object (and 
        // version). If this number is 0 then object+version is not visible as public 
        // through DataPalettes. If it's >0 (which is the same as existence of keywords 
        // in 'extpub') then it's visible.

        // props = {"properties": {},
        //          "pguid": {"type": "keyword"},
        //          "prefix": {"type": "keyword"},
        //          "version": {"type": "integer"},
        //          "lastin": {"type": "integer"},
        //          "groups": {"type": "integer"},
        //          "extpub": {"type": "integer"}}
        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("extpub", tmp);
    }
    
    private void createTables(String indexName, List<IndexingRules> indexingRules) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> mappings = new LinkedHashMap<>();
        doc.put("mappings", mappings);
        // Access (parent)
        createAccessTable(mappings);

        // table = {"data": {}}
        String tableName = getDataTableName();
        Map<String, Object> table = new LinkedHashMap<>();
        mappings.put(tableName, table);

        // table = {"data": {}, "_parent": {"type": "access"}}
        Map<String, Object> tmp = new LinkedHashMap<>();
        tmp.put("type", getAccessTableName());
        table.put("_parent", tmp);

        // table = {"data": {},
        //          "_parent": { "type": "access"},
        //                       "properties": {"guid": {"type": "keyword"}}}
        Map<String, Object> props = new LinkedHashMap<>();
        tmp = new LinkedHashMap<>();
        tmp.put("type", "keyword");
        props.put("guid", tmp);
        table.put("properties", props);

        // table = {"data": {},
        //          "_parent": { "type": "access"},
        //                       "properties": {"guid": {"type": "keyword"},
        //                                     {"otype": {"type": "keyword"},
        //                                     {"otypever": {"type": "integer"},
        //                                     {"oname": {"type": "text"},
        //                                     {"creator": {"type": "keyword"},
        //                                     ...}}}
        final Map<String, Object> keyword = ImmutableMap.of("type", "keyword");
        props.put(SEARCH_OBJ_TYPE, keyword);
        props.put(SEARCH_OBJ_TYPE_VER, ImmutableMap.of("type", "integer"));

        tmp = new LinkedHashMap<>();
        tmp.put("type", "text");
        props.put(OBJ_NAME, tmp);
        props.put(OBJ_CREATOR, keyword);
        props.put(OBJ_COPIER, keyword);
        props.put(OBJ_PROV_MODULE, keyword);
        props.put(OBJ_PROV_METHOD, keyword);
        props.put(OBJ_PROV_MODULE_VERSION, keyword);
        props.put(OBJ_PROV_COMMIT_HASH, keyword);
        props.put(OBJ_MD5, keyword);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "date");
        props.put("timestamp", tmp);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "keyword");
        props.put("prefix", tmp);

        props.put("str_cde", keyword);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("accgrp", tmp);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "integer");
        props.put("version", tmp);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "boolean");
        props.put("islast", tmp);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "boolean");
        props.put("public", tmp);

        tmp = new LinkedHashMap<>();
        tmp.put("type", "boolean");
        props.put("shared", tmp);

        if (!skipFullJson) {
            tmp = new LinkedHashMap<>();
            tmp.put("type", "keyword");
            tmp.put("index", false);
            tmp.put("doc_values", false);
            props.put("ojson", tmp);

            tmp = new LinkedHashMap<>();
            tmp.put("type", "keyword");
            tmp.put("index", false);
            tmp.put("doc_values", false);
            props.put("pjson", tmp);
        }
        for (IndexingRules rules : indexingRules) {
            String propName = getKeyProperty(rules.getKeyName());
            String propType = getEsType(rules.isFullText(), rules.getKeywordType());

            tmp = new LinkedHashMap<>();
            tmp.put("type", propType);
            props.put(propName, tmp);
        }
        makeRequest("PUT", "/" + indexName, doc);
    }
    
    public void close() throws IOException {
        if (restClient != null) {
            restClient.close();
            restClient = null;
        }
    }
}
