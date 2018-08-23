package kbasesearchengine.test.integration;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import kbasesearchengine.common.FileUtil;
import kbasesearchengine.search.FoundHits;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpHost;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

import junit.framework.Assert;
import kbasesearchengine.common.GUID;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventID;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StatusEventType;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.handler.CloneableWorkspaceClientImpl;
import kbasesearchengine.events.handler.WorkspaceEventHandler;
import kbasesearchengine.events.storage.MongoDBStatusEventStorage;
import kbasesearchengine.events.storage.StatusEventStorage;
import kbasesearchengine.main.LineLogger;
import kbasesearchengine.main.IndexerWorker;
import kbasesearchengine.main.IndexerWorkerConfigurator;
import kbasesearchengine.search.AccessFilter;
import kbasesearchengine.search.ElasticIndexingStorage;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.search.MatchFilter;
import kbasesearchengine.search.ObjectData;
import kbasesearchengine.search.PostProcessing;
import kbasesearchengine.system.TypeFileStorage;
import kbasesearchengine.system.FileLister;
import kbasesearchengine.system.ObjectTypeParsingRulesFileParser;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.system.TypeStorage;
import kbasesearchengine.system.TypeMappingParser;
import kbasesearchengine.system.YAMLTypeMappingParser;
import kbasesearchengine.test.common.TestCommon;
import kbasesearchengine.test.controllers.elasticsearch.ElasticSearchController;
import kbasesearchengine.test.controllers.workspace.WorkspaceController;
import kbasesearchengine.test.data.TestDataLoader;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.UObject;
import us.kbase.common.test.controllers.mongo.MongoController;
import us.kbase.test.auth2.authcontroller.AuthController;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.ObjectSaveData;
import us.kbase.workspace.RegisterTypespecParams;
import us.kbase.workspace.RenameObjectParams;
import us.kbase.workspace.SaveObjectsParams;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;

public class IndexerWorkerIntegrationTest {
    
    /* these tests bring up mongodb, elasticsearch, and the workspace and test the worker
     * interactions with those services.
     */

    private static AuthController auth;
    private static IndexerWorker worker = null;
    private static IndexingStorage storage = null;
    private static MongoController mongo;
    private static MongoClient mc;
    private static MongoDatabase db;
    private static ElasticSearchController es;
    private static WorkspaceController ws;
    private static String token1;
    private static int wsid;
    
    private static Path tempDirPath;
    
    @BeforeClass
    public static void prepare() throws Exception {
        TestCommon.stfuLoggers();

        tempDirPath = Paths.get(TestCommon.getTempDir()).resolve("IndexerWorkerIntegrationTest");
        // should refactor to just use NIO at some point
        FileUtils.deleteQuietly(tempDirPath.toFile());
        tempDirPath.toFile().mkdirs();

        // set up mongo
        mongo = new MongoController(
                TestCommon.getMongoExe(),
                tempDirPath,
                TestCommon.useWiredTigerEngine());
        mc = new MongoClient("localhost:" + mongo.getServerPort());
        final String dbName = "DataStatus";
        db = mc.getDatabase(dbName);
        
        // set up auth
        auth = new AuthController(
                TestCommon.getJarsDir(),
                "localhost:" + mongo.getServerPort(),
                "IndexerIntTestAuth",
                tempDirPath);
        final URL authURL = new URL("http://localhost:" + auth.getServerPort() + "/testmode");
        System.out.println("started auth server at " + authURL);
        TestCommon.createAuthUser(authURL, "user1", "display1");
        TestCommon.createAuthUser(authURL, "user2", "display2");
        token1 = TestCommon.createLoginToken(authURL, "user1");
        final String token2 = TestCommon.createLoginToken(authURL, "user2");
        final AuthToken userToken = new AuthToken(token1, "user1");
        final AuthToken wsadmintoken = new AuthToken(token2, "user2");
        
        // set up elastic search
        es = new ElasticSearchController(TestCommon.getElasticSearchExe(), tempDirPath);
        
        // set up Workspace
        ws = new WorkspaceController(
                TestCommon.getWorkspaceVersion(),
                TestCommon.getJarsDir(),
                "localhost:" + mongo.getServerPort(),
                "MOPTestWSDB",
                dbName,
                wsadmintoken.getUserName(),
                authURL,
                tempDirPath);
        System.out.println("Started workspace on port " + ws.getServerPort());
        
        final Path typesDir = Paths.get(TestCommon.TYPES_REPO_DIR);
        final Path mappingsDir = Paths.get(TestCommon.TYPE_MAP_REPO_DIR);
        
        URL wsUrl = new URL("http://localhost:" + ws.getServerPort());

        final String esIndexPrefix = "test_" + System.currentTimeMillis() + ".";
        final HttpHost esHostPort = new HttpHost("localhost", es.getServerPort());
        final LineLogger logger = new LineLogger() {
            @Override
            public void logInfo(String line) {
                System.out.println(line);
            }
            @Override
            public void logError(String line) {
                System.err.println(line);
            }
            @Override
            public void logError(Throwable error) {
                error.printStackTrace();
            }
            @Override
            public void timeStat(GUID guid, long loadMs, long parseMs, long indexMs) {
            }
        };
        final Map<String, TypeMappingParser> parsers = ImmutableMap.of(
                "yaml", new YAMLTypeMappingParser());
        final TypeStorage ss = new TypeFileStorage(typesDir, mappingsDir,
                new ObjectTypeParsingRulesFileParser(), parsers, new FileLister(), logger);
        
        final StatusEventStorage eventStorage = new MongoDBStatusEventStorage(db);
        final WorkspaceClient wsClient = new WorkspaceClient(wsUrl, wsadmintoken);
        wsClient.setIsInsecureHttpConnectionAllowed(true); //TODO SEC only do if http
        
        final WorkspaceEventHandler weh = new WorkspaceEventHandler(
                new CloneableWorkspaceClientImpl(wsClient));
        
        final ElasticIndexingStorage esStorage = new ElasticIndexingStorage(esHostPort,
                FileUtil.getOrCreateSubDir(tempDirPath.toFile(), "esbulk"));
        esStorage.setIndexNamePrefix(esIndexPrefix);
        storage = esStorage;
        
        final IndexerWorkerConfigurator.Builder wrkCfg = IndexerWorkerConfigurator.getBuilder(
                "test", tempDirPath.resolve("WorkerTemp"), logger)
                .withStorage(eventStorage, ss, esStorage)
                .withEventHandler(weh);
        
        worker = new IndexerWorker(wrkCfg.build());
        
        loadTypes(wsUrl, wsadmintoken);
        wsid = (int) loadTestData(wsUrl, userToken);
    }
    
    private static long loadTestData(final URL wsUrl, final AuthToken usertoken)
            throws IOException, JsonClientException {
        final WorkspaceClient wc = new WorkspaceClient(wsUrl, usertoken);
        wc.setIsInsecureHttpConnectionAllowed(true);
        final long wsid = wc.createWorkspace(new CreateWorkspaceParams().withWorkspace("MOPTest"))
                .getE1();
        
        loadData(wc, wsid, "Narr", "KBaseNarrative.Narrative-1.0", "NarrativeObject1");
        loadData(wc, wsid, "Narr", "KBaseNarrative.Narrative-1.0", "NarrativeObject2");
        loadData(wc, wsid, "Narr", "KBaseNarrative.Narrative-1.0", "NarrativeObject3");
        loadData(wc, wsid, "Narr", "KBaseNarrative.Narrative-1.0", "NarrativeObject4");
        loadData(wc, wsid, "Narr", "KBaseNarrative.Narrative-1.0", "NarrativeObject5");
        
        loadData(wc, wsid, "Assy", "KBaseGenomeAnnotations.Assembly-1.0", "AssemblyObject");
        loadData(wc, wsid, "Genome", "KBaseGenomes.Genome-1.0", "GenomeObject");
        loadData(wc, wsid, "Paired", "KBaseFile.PairedEndLibrary-1.0", "PairedEndLibraryObject");
        loadData(wc, wsid, "reads.2", "KBaseFile.SingleEndLibrary-1.0", "SingleEndLibraryObject");
        loadData(wc, wsid, "FBAMod", "KBaseFBA.FBAModel", "FBAModel01");
        return wsid;
    }
    
    private static void loadTypes(final URL wsURL, final AuthToken wsadmintoken) throws Exception {
        final WorkspaceClient wc = new WorkspaceClient(wsURL, wsadmintoken);
        wc.setIsInsecureHttpConnectionAllowed(true);
        loadType(wc, "KBaseFile", "KBaseFile_ci_1477697265343",
                Arrays.asList("SingleEndLibrary", "PairedEndLibrary"));
        loadType(wc, "KBaseGenomeAnnotations", "KBaseGenomeAnnotations_ci_1471308269061",
                Arrays.asList("Assembly"));
        loadType(wc, "KBaseGenomes", "KBaseGenomes_ci_1482357978770", Arrays.asList("Genome"));
        loadType(wc, "KBaseNarrative", "KBaseNarrative_ci_1436483557716",
                Arrays.asList("Narrative"));
        loadType(wc, "KBaseFBA", "KBaseFBA",
                Arrays.asList("FBAModel"));
    }

    private static void loadData(
            final WorkspaceClient wc,
            final long wsid,
            final String name,
            final String type,
            final String fileName)
            throws JsonParseException, JsonMappingException, IOException, JsonClientException {
        final String data = TestDataLoader.load(fileName);
        final Object objdata = new ObjectMapper().readValue(data, Object.class);
        wc.saveObjects(new SaveObjectsParams()
                .withId(wsid)
                .withObjects(Arrays.asList(new ObjectSaveData()
                        .withData(new UObject(objdata))
                        .withName(name)
                        .withType(type))));
    }

    private static void loadType(
            final WorkspaceClient wc,
            final String module,
            final String fileName,
            final List<String> types)
            throws IOException, JsonClientException {
        final String typespec = TestDataLoader.load(fileName);
        wc.requestModuleOwnership(module);
        final Map<String, String> cmd = new HashMap<>();
        cmd.put("command", "approveModRequest");
        cmd.put("module", module);
        wc.administer(new UObject(cmd));
        wc.registerTypespec(new RegisterTypespecParams()
                .withDryrun(0L)
                .withSpec(typespec)
                .withNewTypes(types));
        wc.releaseModule(module);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        final boolean deleteTempFiles = TestCommon.getDeleteTempFiles();
        if (auth != null) {
            auth.destroy(deleteTempFiles);
        }
        if (ws != null) {
            ws.destroy(deleteTempFiles);
        }
        if (mc != null) {
            mc.close();
        }
        if (mongo != null) {
            mongo.destroy(deleteTempFiles);
        }
        if (es != null) {
            es.destroy(deleteTempFiles);
        }
        if (tempDirPath != null && tempDirPath.toFile().exists() && deleteTempFiles) {
            FileUtils.deleteQuietly(tempDirPath.toFile());
        }
    }
    
    @Before
    public void init() throws Exception {
        TestCommon.destroyDB(db);
        storage.dropData();
    }
    
    @Test
    public void testGenomeManually() throws Exception {
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseGenomes.Genome"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(wsid)
                .withNullableObjectID("3")
                .withNullableVersion(1)
                .withNullableisPublic(false)
                .build(),
                new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC)
                .build();
        worker.processEvent(ev);

        final PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;

        {
            final List<String> objectTypes = ImmutableList.of("Genome");
            final FoundHits hits = storage.searchObjects(objectTypes,
                    MatchFilter.getBuilder().build(), null,
                    AccessFilter.create().withAdmin(true), null, pp
            );
            // main/parent object is indexed
            Assert.assertEquals("expected 1 genome", 1, hits.guids.size());
        }

        {
            final List<String> objectTypes = ImmutableList.of("GenomeFeature");
            final FoundHits hits = storage.searchObjects(objectTypes,
                    MatchFilter.getBuilder().build(), null,
                    AccessFilter.create().withAdmin(true), null, pp
                    );
            // sub-objects are indexed
            Assert.assertEquals("expected 30 genome features", 30, hits.guids.size());
        }

        {
            final List<String> objectTypes = ImmutableList.of("Assembly");
            String message = "Exception check failed!";
            try {
                storage.searchObjects(objectTypes,
                        MatchFilter.getBuilder().build(), null,
                        AccessFilter.create().withAdmin(true), null, pp
                );
            } catch(IOException ex) {
                message = ex.getMessage();
            } finally {
                // referenced objects are not recursively indexed
                Assert.assertEquals("not the expected error message",
                        "No indexes exist for search type Assembly",
                        message);
            }
        }

        {
            List<String> objectTypes = ImmutableList.of("AssemblyContig");
            String message = "Exception check failed!";
            try {
                storage.searchObjects(objectTypes,
                        MatchFilter.getBuilder().build(), null,
                        AccessFilter.create().withAdmin(true), null, pp
                );
            } catch(IOException ex) {
                message = ex.getMessage();
            } finally {
                // referenced objects are not recursively indexed
                Assert.assertEquals("not the expected error message",
                        "No indexes exist for search type AssemblyContig",
                        message);
            }
        }

        // verify a feature
        final String query = "TrkA";
        final Map<String, Integer> typeToCount = storage.searchTypes(
                MatchFilter.getBuilder().withNullableFullTextInAll(query).build(), 
                AccessFilter.create().withAdmin(true));
        Assert.assertEquals("expected 1 type (GenomeFeature)", 1, typeToCount.size());

        final List<String> types = ImmutableList.of(typeToCount.keySet().iterator().next());

        final Set<GUID> guids = storage.searchIds(types,
                MatchFilter.getBuilder().withNullableFullTextInAll(query).build(), null, 
                AccessFilter.create().withAdmin(true), null).guids;
        Assert.assertEquals("expected 1 guid", 1, guids.size());
        final ObjectData obj = storage.getObjectsByIds(guids, pp).get(0);
        Assert.assertEquals("wrong feature id", "PROKKA_00027", obj.getGUID().getSubObjectId());
    }


    @Test
    public void testFBAModelManually() throws Exception {
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseFBA.FBAModel"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                        .withNullableAccessGroupID(wsid)
                        .withNullableObjectID("6")
                        .withNullableVersion(1)
                        .withNullableisPublic(false)
                        .build(),
                new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC)
                .build();
        worker.processEvent(ev);

        final PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;

        // verify that the referenced genome object was not recursively indexed
        {
            final List<String> objectTypes = ImmutableList.of("Genome");
            String message = "Exception check failed!";
            try {
                storage.searchObjects(objectTypes,
                        MatchFilter.getBuilder().build(), null,
                        AccessFilter.create().withAdmin(true), null, pp
                );
            } catch(IOException ex) {
                message = ex.getMessage();
            } finally {
                Assert.assertEquals("not the expected error message",
                        "No indexes exist for search type Genome",
                        message);
            }
        }

        {
            final List<String> objectTypes = ImmutableList.of("FBAModel");
            final FoundHits hits = storage.searchObjects(objectTypes,
                    MatchFilter.getBuilder().build(), null,
                    AccessFilter.create().withAdmin(true), null, pp
            );
            // main/parent object is indexed
            Assert.assertEquals("expected 1 FBAModel", 1, hits.guids.size());

            final ObjectData data = hits.objects.get(0);

            // verify that lookup transforms recursively obtained genome data from data source
            Assert.assertEquals("expected scientific_name", "test",
                    data.getKeyProperties().get("scientific_name"));
            Assert.assertEquals("expected taxonomy", "",
                    data.getKeyProperties().get("taxonomy"));
            Assert.assertEquals("expected genome_name", "Unknown",
                    data.getKeyProperties().get("genome_name"));
        }
    }

    private void indexFewVersions(final StoredStatusEvent evid) throws Exception {
        final StatusEvent ev = evid.getEvent();
        for (int i = Math.max(1, ev.getVersion().get() - 5); i <= ev.getVersion().get(); i++) {
            final StoredStatusEvent ev2 = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                    ev.getStorageObjectType().get(),
                    ev.getTimestamp(),
                    ev.getEventType())
                    .withNullableAccessGroupID(ev.getAccessGroupId().get())
                    .withNullableObjectID(ev.getAccessGroupObjectId().get())
                    .withNullableVersion(i)
                    .withNullableisPublic(ev.isPublic().get())
                    .build(),
                    evid.getID(),
                    StatusEventProcessingState.UNPROC)
                    .build();
            worker.processEvent(ev2);
        }
    }

    private void checkSearch(
            final int expectedCount,
            final List<String> types,
            final String query,
            final int accessGroupId,
            final boolean debugOutput)
            throws Exception {
        final Set<GUID> ids = storage.searchIds(types,
                MatchFilter.getBuilder().withNullableFullTextInAll(query).build(), null,
                AccessFilter.create().withAccessGroups(accessGroupId), null).guids;
        if (debugOutput) {
            final PostProcessing pp = new PostProcessing();
            pp.objectData = true;
            pp.objectKeys = true;
            System.out.println("DEBUG: " + storage.getObjectsByIds(ids, pp));
        }
        Assert.assertEquals(1, ids.size());
    }

    @Test
    public void testNarrativeManually() throws Exception {
        final StatusEvent ev = StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseNarrative.Narrative"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(wsid)
                .withNullableObjectID("1")
                .withNullableVersion(5)
                .withNullableisPublic(false)
                .build();
        indexFewVersions(StoredStatusEvent.getBuilder(ev, new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC).build());
        checkSearch(1, ImmutableList.of("Narrative"), "tree", wsid, false);
        checkSearch(1, ImmutableList.of("Narrative"), "species", wsid, false);
        /*indexFewVersions(new ObjectStatusEvent("-1", "WS", 10455, "1", 78, null,
                System.currentTimeMillis(), "KBaseNarrative.Narrative",
                ObjectStatusEventType.CREATED, false));
        checkSearch(1, "Narrative", "Catalog.migrate_module_to_new_git_url", 10455, false);
        checkSearch(1, "Narrative", "Super password!", 10455, false);
        indexFewVersions(new ObjectStatusEvent("-1", "WS", 480, "1", 254, null,
                System.currentTimeMillis(), "KBaseNarrative.Narrative",
                ObjectStatusEventType.CREATED, false));
        checkSearch(1, "Narrative", "weird text", 480, false);
        checkSearch(1, "Narrative", "functionality", 480, false);*/
    }

    @Test
    public void testReadsManually() throws Exception {
        final StatusEvent ev = StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseFile.PairedEndLibrary"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(wsid)
                .withNullableObjectID("4")
                .withNullableVersion(1)
                .withNullableisPublic(false)
                .build();
        indexFewVersions(StoredStatusEvent.getBuilder(ev, new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC).build());
        checkSearch(1, ImmutableList.of("PairedEndLibrary"), "Illumina", wsid, true);
        checkSearch(1, ImmutableList.of("PairedEndLibrary"), "sample1se.fastq.gz", wsid, false);
        final StatusEvent ev2 = StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseFile.SingleEndLibrary"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(wsid)
                .withNullableObjectID("5")
                .withNullableVersion(1)
                .withNullableisPublic(false)
                .build();
        indexFewVersions(StoredStatusEvent.getBuilder(ev2, new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC).build());
        checkSearch(1, ImmutableList.of("SingleEndLibrary"), "PacBio", wsid, true);
        checkSearch(1, ImmutableList.of("SingleEndLibrary"), "reads.2", wsid, false);
    }

    @Test
    public void testWorkspaceDeletion() throws Exception {
        URL wsUrl = new URL("http://localhost:" + ws.getServerPort());
        final AuthToken wsadmintoken = new AuthToken(token1, "user1");
        final WorkspaceClient wc = new WorkspaceClient(wsUrl, wsadmintoken);
        wc.setIsInsecureHttpConnectionAllowed(true); //TODO SEC only do if http
        final String objName = "GenomeToDelete";

        // create a workspace with an object
        final long wsidToDelete = wc.createWorkspace(new CreateWorkspaceParams().withWorkspace("WorkspaceDeleteTest"))
                .getE1();

        loadData(wc, wsidToDelete, objName, "KBaseGenomes.Genome-1.0", "GenomeObject");

        // create an event for the object
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseGenomes.Genome"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                        .withNullableAccessGroupID((int)wsidToDelete)
                        .withNullableObjectID("1")
                        .withNullableVersion(1)
                        .withNullableisPublic(false)
                        .build(),
                new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC)
                .build();

        // delete workspace
        wc.deleteWorkspace(new WorkspaceIdentity().withId(wsidToDelete));

        // process event
        worker.processEvent(ev);

        List<String> objectTypes = ImmutableList.of("Genome");
        PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;

        // object was not indexed
        try {
            FoundHits hits = storage.searchIds(objectTypes,
                    MatchFilter.getBuilder().
                            withNullableObjectName(objName).build(),
                    null,
                    AccessFilter.create().withAdmin(true), null);

            // to compensate for side-effect when testGenomeManually
            // is executed before this test. This side-effect needs to be fixed.
            Assert.assertTrue(hits.guids.size() == 0);
        } catch (IOException ex) {
            Assert.assertTrue("No indexes exist for search type Genome".equals(ex.getMessage()));
        }
    }

    @Test
    public void testNonExistentWorkspaceCase() throws Exception {
        // create an event for a workspace that does not exist
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseGenomes.Genome"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                        .withNullableAccessGroupID(10000)
                        .withNullableObjectID("1")
                        .withNullableVersion(1)
                        .withNullableisPublic(false)
                        .build(),
                new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC)
                .build();

        // process event
        worker.processEvent(ev);

        List<String> objectTypes = ImmutableList.of("Genome");
        PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;

        // object was not indexed
        try {
            GUID guid = new GUID("WS", 10000, "1", 1, null, null);
            List<ObjectData> data = storage.getObjectsByIds(ImmutableSet.of(guid));

            // to compensate for side-effect when testGenomeManually
            // is executed before this test. This side-effect needs to be fixed.
            Assert.assertTrue(data.size() == 0);
        } catch (IOException ex) {
            Assert.assertTrue("No indexes exist for search type Genome".equals(ex.getMessage()));
        }
    }


    @Test
    public void testRenameObject() throws Exception {
        URL wsUrl = new URL("http://localhost:" + ws.getServerPort());
        final AuthToken wsadmintoken = new AuthToken(token1, "user1");
        final WorkspaceClient wc = new WorkspaceClient(wsUrl, wsadmintoken);
        wc.setIsInsecureHttpConnectionAllowed(true); //TODO SEC only do if http
        final String objName = "GenomeToRename";
        final String newObjName = "GenomeRenamed";

        // create an object with some name
        final long wsidForRenameObject = wc.createWorkspace(new CreateWorkspaceParams().withWorkspace("RenameObjectTest"))
                .getE1();
        loadData(wc, wsidForRenameObject, objName, "KBaseGenomes.Genome-1.0", "GenomeObject");

        // create event for object
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("WS", "KBaseGenomes.Genome"),
                Instant.now(),
                StatusEventType.NEW_VERSION)
                        .withNullableAccessGroupID((int)wsidForRenameObject)
                        .withNullableObjectID("1")
                        .withNullableVersion(1)
                        .withNullableisPublic(false)
                        .build(),
                new StatusEventID("-1"),
                StatusEventProcessingState.UNPROC)
                .build();

        // rename the object
        wc.renameObject(new RenameObjectParams().
                                withObj(new ObjectIdentity().
                                               withWsid(wsidForRenameObject).
                                               withName(objName)).
                                withNewName(newObjName));

        // then process event
        worker.processEvent(ev);

        List<String> objectTypes = ImmutableList.of("Genome");
        PostProcessing pp = new PostProcessing();
        pp.objectData = true;
        pp.objectKeys = true;

        FoundHits hits = storage.searchIds(objectTypes,
                MatchFilter.getBuilder().
                        withNullableObjectName(newObjName).build(),
                null,
                AccessFilter.create().withAdmin(true), null);

        List<ObjectData> data = storage.getObjectsByIds(
                hits.guids, pp);

        // processed event should reflect new object name
        Assert.assertEquals("incorrect genome name",
                newObjName,
                data.get(0).getObjectName().get());
    }
}
