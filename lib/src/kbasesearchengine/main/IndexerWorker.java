package kbasesearchengine.main;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;
import com.google.common.base.Optional;

import kbasesearchengine.common.GUID;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.IndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.Retrier;
import kbasesearchengine.events.exceptions.UnprocessableEventIndexingException;
import kbasesearchengine.events.handler.EventHandler;
import kbasesearchengine.events.handler.ResolvedReference;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.events.storage.StatusEventStorage;
import kbasesearchengine.parse.KeywordParser;
import kbasesearchengine.parse.ObjectParseException;
import kbasesearchengine.parse.ObjectParser;
import kbasesearchengine.parse.ParsedObject;
import kbasesearchengine.parse.KeywordParser.ObjectLookupProvider;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.system.TypeStorage;
import kbasesearchengine.tools.Utils;

public class IndexerWorker {
    
    private static final int RETRY_COUNT = 5;
    private static final int RETRY_SLEEP_MS = 1000;
    private static final List<Integer> RETRY_FATAL_BACKOFF_MS = Arrays.asList(
            1000, 2000, 4000, 8000, 16000);
    
    private final String id;
    private final File rootTempDir;
    private final StatusEventStorage storage;
    private final TypeStorage typeStorage;
    private final IndexingStorage indexingStorage;
    private final LineLogger logger;
    private final Map<String, EventHandler> eventHandlers = new HashMap<>();
    private ScheduledExecutorService executor = null;
    
    private final Retrier retrier = new Retrier(RETRY_COUNT, RETRY_SLEEP_MS,
            RETRY_FATAL_BACKOFF_MS,
            (retrycount, event, except) -> logError(retrycount, event, except));

    public IndexerWorker(
            final String id,
            final List<EventHandler> eventHandlers,
            final StatusEventStorage storage,
            final IndexingStorage indexingStorage,
            final TypeStorage typeStorage,
            final File tempDir,
            final LineLogger logger) {
        Utils.notNullOrEmpty("id", "id cannot be null or the empty string");
        Utils.nonNull(logger, "logger");
        Utils.nonNull(indexingStorage, "indexingStorage");
        this.id = id;
        this.logger = logger;
        this.rootTempDir = tempDir;
        
        eventHandlers.stream().forEach(eh -> this.eventHandlers.put(eh.getStorageCode(), eh));
        this.storage = storage;
        this.typeStorage = typeStorage;
        this.indexingStorage = indexingStorage;
    }
    
    /**
     * For tests only !!!
     */
    public IndexerWorker(
            final String id,
            final IndexingStorage indexingStorage,
            final TypeStorage typeStorage,
            final File tempDir,
            final LineLogger logger) {
        Utils.notNullOrEmpty("id", "id cannot be null or the empty string");
        Utils.nonNull(logger, "logger");
        this.id = id;
        this.storage = null;
        this.rootTempDir = tempDir;
        this.logger = logger;
        this.typeStorage = typeStorage;
        this.indexingStorage = indexingStorage;
    }
    
    private File getTempSubDir(final String subName) {
        return getTempSubDir(rootTempDir, subName);
    }
    
    public static File getTempSubDir(final File rootTempDir, String subName) {
        File ret = new File(rootTempDir, subName);
        if (!ret.exists()) {
            ret.mkdirs();
        }
        return ret;
    }
    
    public void startIndexer() {
        //TODO TEST add a way to inject an executor for testing purposes
        executor = Executors.newSingleThreadScheduledExecutor();
        // may want to make this configurable
        executor.scheduleAtFixedRate(new IndexerRunner(), 0, 1000, TimeUnit.MILLISECONDS);
    }
    
    private class IndexerRunner implements Runnable {

        @Override
        public void run() {
            boolean processedEvent = true;
            while (processedEvent) {
                processedEvent = false;
                try {
                    // keep processing events until there are none left
                    processedEvent = performOneTick();
                } catch (InterruptedException | FatalIndexingException e) {
                    logError(ErrorType.FATAL, e);
                    executor.shutdown();
                } catch (Exception e) {
                    logError(ErrorType.UNEXPECTED, e);
                }
            }
        }
    }
    
    public void stopIndexer() {
        executor.shutdown();
    }
    
    private enum ErrorType {
        STD, FATAL, UNEXPECTED;
    }
    
    private void logError(final ErrorType errtype, final Throwable e) {
        Utils.nonNull(errtype, "errtype");
        final String msg;
        if (ErrorType.FATAL.equals(errtype)) {
            msg = "Fatal error in indexer, shutting down: ";
        } else if (ErrorType.STD.equals(errtype)) {
            msg = "Error in indexer: ";
        } else if (ErrorType.UNEXPECTED.equals(errtype)) {
            msg = "Unexpected error in indexer: ";
        } else {
            throw new RuntimeException("Unknown error type: " + errtype);
        }
        logError(msg, e);
    }

    private void logError(final String msg, final Throwable e) {
        final String firstStackLine = e.getStackTrace().length == 0 ? "<not-available>" : 
                e.getStackTrace()[0].toString();
        logger.logError(msg + e + ", " + firstStackLine);
        logger.logError(e); //TODO LOG split into lines with id
    }

    private void logError(
            final int retrycount,
            final Optional<StoredStatusEvent> event,
            final RetriableIndexingException e) {
        final String msg;
        if (event.isPresent()) {
            msg = String.format("Retriable error in indexer for event %s %s, retry %s: ",
                    event.get().getEvent().getEventType(), event.get().getId().getId(),
                    retrycount);
        } else {
            msg = String.format("Retriable error in indexer, retry %s: ", retrycount);
        }
        logError(msg, e);
    }
    
    private boolean performOneTick() throws InterruptedException, IndexingException {
        final Optional<StoredStatusEvent> optEvent = retrier.retryFunc(
                s -> s.getAndSetProcessingState(StatusEventProcessingState.READY,
                        StatusEventProcessingState.PROC, Instant.now(), id),
                storage, null);
        boolean processedEvent = false;
        if (optEvent.isPresent()) {
            final StoredStatusEvent parentEvent = optEvent.get();
            //TODO NOW getEventHandler indexing exception should be caught and the event skipped
            if (getEventHandler(parentEvent).isExpandable(parentEvent)) {
                logger.logInfo(String.format("[Indexer] Expanding event %s %s",
                        parentEvent.getEvent().getEventType(), parentEvent.getId().getId()));
                try {
                    final Iterator<StatusEvent> er = retrier.retryFunc(
                            e -> getSubEventIterator(e), parentEvent, parentEvent);
                    storage.setProcessingState(parentEvent, processEvents(parentEvent, er)); //TODO NOW retry
                } catch (IndexingException e) {
                    markAsVisitedFailedPostError(parentEvent);
                    handleException("Error expanding parent event", parentEvent, e);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    // don't know how to respond to anything else, so mark event failed and keep going
                    logError(ErrorType.UNEXPECTED, e);
                    markAsVisitedFailedPostError(parentEvent);
                }
            } else {
                try {
                    storage.setProcessingState(parentEvent, processEvent(parentEvent)); //TODO NOW retry
                } catch (FatalIndexingException e) {
                    markAsVisitedFailedPostError(parentEvent);
                    throw e;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    // don't know how to respond to anything else, so mark event failed and keep going
                    logError(ErrorType.UNEXPECTED, e);
                    markAsVisitedFailedPostError(parentEvent);
                }
            }
            processedEvent = true;
        }
        return processedEvent;
    }
    
    private Iterator<StatusEvent> getSubEventIterator(final StoredStatusEvent ev)
            throws IndexingException, RetriableIndexingException {
        try {
            return getEventHandler(ev).expand(ev).iterator();
        } catch (IndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        } catch (RetriableIndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        }
    }

    // assumes something has already failed, so if this fails as well something is really 
    // wrong and we bail.
    private void markAsVisitedFailedPostError(final StoredStatusEvent parentEvent)
            throws FatalIndexingException {
        try {
            storage.setProcessingState(parentEvent, StatusEventProcessingState.FAIL); //TODO NOW retry
        } catch (Exception e) {
            //ok then we're screwed
            throw new FatalIndexingException("Can't mark events as failed: " + e.getMessage(), e);
        }
    }

    // returns whether processing was successful or not.
    private StatusEventProcessingState processEvents(
            final StoredStatusEvent parentEvent,
            final Iterator<StatusEvent> expanditer)
            throws InterruptedException, FatalIndexingException {
        StatusEventProcessingState allsuccess = StatusEventProcessingState.INDX;
        while (expanditer.hasNext()) {
            //TODO EVENT insert sub event into db - need to ensure not inserted twice on reprocess - use parent id
            final StatusEventProcessingState res;
            try {
                final StatusEvent subev = retrier.retryFunc(
                        i -> getNextSubEvent(i), expanditer, parentEvent);
                //TODO NOW store parent ID
                final StoredStatusEvent ev = retrier.retryFunc(
                        s -> s.store(subev, StatusEventProcessingState.PROC), //TODO WORKER store ID
                        storage, parentEvent);
                res = processEvent(ev);
                retrier.retryFunc(s -> s.setProcessingState(ev, res), storage, ev);
            } catch (IndexingException e) {
                // TODO EVENT mark sub event as failed
                handleException("Error getting event from data storage", parentEvent, e);
                return StatusEventProcessingState.FAIL;
            }
            if (StatusEventProcessingState.FAIL.equals(res)) {
                allsuccess = StatusEventProcessingState.FAIL;
            }
        }
        return allsuccess;
    }

    private StatusEventProcessingState processEvent(final StoredStatusEvent ev)
            throws InterruptedException, FatalIndexingException {
        final Optional<StorageObjectType> type = ev.getEvent().getStorageObjectType();
        if (type.isPresent() && !isStorageTypeSupported(ev)) {
            logger.logInfo("[Indexer] skipping " + ev.getEvent().getEventType() + ", " + 
                    toLogString(type) + ev.getEvent().toGUID());
            return StatusEventProcessingState.UNINDX;
        }
        logger.logInfo("[Indexer] processing " + ev.getEvent().getEventType() + ", " + 
                toLogString(type) + ev.getEvent().toGUID() + "...");
        final long time = System.currentTimeMillis();
        try {
            retrier.retryCons(e -> processOneEvent(e), ev, ev);
        } catch (IndexingException e) {
            handleException("Error processing event", ev, e);
            return StatusEventProcessingState.FAIL;
        }
        logger.logInfo("[Indexer]   (total time: " + (System.currentTimeMillis() - time) + "ms.)");
        return StatusEventProcessingState.INDX;
    }
    
    private StatusEvent getNextSubEvent(Iterator<StatusEvent> iter)
            throws IndexingException, RetriableIndexingException {
        try {
            return iter.next();
        } catch (IndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        } catch (RetriableIndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        }
    }
    
    private void handleException(
            final String error,
            final StoredStatusEvent event,
            final IndexingException exception)
            throws FatalIndexingException {
        if (exception instanceof FatalIndexingException) {
            throw (FatalIndexingException) exception;
        } else {
            final String msg = error + String.format(" for event %s %s: ",
                    event.getEvent().getEventType(), event.getId().getId());
            logError(msg, exception);
        }
    }

    private String toLogString(final Optional<StorageObjectType> type) {
        if (!type.isPresent()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(type.get().getStorageCode());
        sb.append(":");
        sb.append(type.get().getType());
        if (type.get().getVersion().isPresent()) {
            sb.append("-");
            sb.append(type.get().getVersion().get());
        }
        sb.append(", ");
        return sb.toString();
    }

    private EventHandler getEventHandler(final StoredStatusEvent ev)
            throws UnprocessableEventIndexingException {
        return getEventHandler(ev.getEvent().getStorageCode());
    }
    
    private EventHandler getEventHandler(final GUID guid)
            throws UnprocessableEventIndexingException {
        return getEventHandler(guid.getStorageCode());
    }
    
    private EventHandler getEventHandler(final String storageCode)
            throws UnprocessableEventIndexingException {
        if (!eventHandlers.containsKey(storageCode)) {
            throw new UnprocessableEventIndexingException(String.format(
                    "No event handler for storage code %s is registered", storageCode));
        }
        return eventHandlers.get(storageCode);
    }

    public void processOneEvent(final StoredStatusEvent evid)
            throws IndexingException, InterruptedException, RetriableIndexingException {
        try {
            final StatusEvent ev = evid.getEvent();
            switch (ev.getEventType()) {
            case NEW_VERSION:
                GUID pguid = ev.toGUID();
                boolean indexed = indexingStorage.checkParentGuidsExist(new LinkedHashSet<>(
                        Arrays.asList(pguid))).get(pguid);
                if (indexed) {
                    logger.logInfo("[Indexer]   skipping " + pguid +
                            " creation (already indexed)");
                    // TODO: we should fix public access for all sub-objects too (maybe already works. Anyway, ensure all subobjects are set correctly as well as the parent)
                    if (ev.isPublic().get()) {
                        publish(pguid);
                    } else {
                        unpublish(pguid);
                    }
                } else {
                    indexObject(pguid, ev.getStorageObjectType().get(), ev.getTimestamp(),
                            ev.isPublic().get(), null, new LinkedList<>());
                }
                break;
            // currently unused
//            case DELETED:
//                unshare(ev.toGUID(), ev.getAccessGroupId().get());
//                break;
            case DELETE_ALL_VERSIONS:
                deleteAllVersions(ev.toGUID());
                break;
            case UNDELETE_ALL_VERSIONS:
                undeleteAllVersions(ev.toGUID());
                break;
                //TODO DP reenable if we support DPs
//            case SHARED:
//                share(ev.toGUID(), ev.getTargetAccessGroupId());
//                break;
                //TODO DP reenable if we support DPs
//            case UNSHARED:
//                unshare(ev.toGUID(), ev.getTargetAccessGroupId());
//                break;
            case RENAME_ALL_VERSIONS:
                renameAllVersions(ev.toGUID(), ev.getNewName().get());
                break;
            case PUBLISH_ALL_VERSIONS:
                publishAllVersions(ev.toGUID());
                break;
            case UNPUBLISH_ALL_VERSIONS:
                unpublishAllVersions(ev.toGUID());
                break;
            default:
                throw new UnprocessableEventIndexingException(
                        "Unsupported event type: " + ev.getEventType());
            }
        } catch (IOException e) {
            // may want to make IndexingStorage throw more specific exceptions, but this will work
            // for now. Need to look more carefully at the code before that happens.
            throw new RetriableIndexingException(e.getMessage(), e);
        }
    }

    // returns false if a non-fatal error prevents retrieving the info
    public boolean isStorageTypeSupported(final StoredStatusEvent ev)
            throws InterruptedException, FatalIndexingException {
        try {
            return retrier.retryFunc(
                    t -> !typeStorage.listObjectTypesByStorageObjectType(
                            ev.getEvent().getStorageObjectType().get()).isEmpty(),
                    ev, ev);
        } catch (IndexingException e) {
            handleException("Error retrieving type info", ev, e);
            return false;
        }
    }
    
    private void indexObject(
            final GUID guid,
            final StorageObjectType storageObjectType,
            final Instant timestamp,
            final boolean isPublic,
            ObjectLookupProvider indexLookup,
            final List<GUID> objectRefPath) 
            throws IndexingException, InterruptedException, RetriableIndexingException {
        long t1 = System.currentTimeMillis();
        final File tempFile;
        try {
            tempFile = ObjectParser.prepareTempFile(getTempSubDir(guid.getStorageCode()));
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(e.getMessage(), e);
        }
        if (indexLookup == null) {
            indexLookup = new MOPLookupProvider();
        }
        try {
            // make a copy to avoid mutating the caller's path
            final LinkedList<GUID> newRefPath = new LinkedList<>(objectRefPath);
            newRefPath.add(guid);
            final EventHandler handler = getEventHandler(guid);
            final SourceData obj = handler.load(newRefPath, tempFile.toPath());
            long loadTime = System.currentTimeMillis() - t1;
            logger.logInfo("[Indexer]   " + guid + ", loading time: " + loadTime + " ms.");
            logger.timeStat(guid, loadTime, 0, 0);
            List<ObjectTypeParsingRules> parsingRules = 
                    typeStorage.listObjectTypesByStorageObjectType(storageObjectType);
            for (ObjectTypeParsingRules rule : parsingRules) {
                final long t2 = System.currentTimeMillis();
                final Map<GUID, ParsedObject> guidToObj = new LinkedHashMap<>();
                final String parentJson = parseObjects(guid, indexLookup,
                        newRefPath, obj, rule, guidToObj);
                long parsingTime = System.currentTimeMillis() - t2;
                logger.logInfo("[Indexer]   " + rule.getGlobalObjectType() + ", parsing " +
                        "time: " + parsingTime + " ms.");
                long t3 = System.currentTimeMillis();
                indexObjectInStorage(guid, timestamp, isPublic, obj, rule, guidToObj, parentJson);
                long indexTime = System.currentTimeMillis() - t3;
                logger.logInfo("[Indexer]   " + rule.getGlobalObjectType() + ", indexing " +
                        "time: " + indexTime + " ms.");
                logger.timeStat(guid, 0, parsingTime, indexTime);
            }
        } finally {
            tempFile.delete();
        }
    }

    private void indexObjectInStorage(
            final GUID guid,
            final Instant timestamp,
            final boolean isPublic,
            final SourceData obj,
            final ObjectTypeParsingRules rule,
            final Map<GUID, ParsedObject> guidToObj,
            final String parentJson)
            throws InterruptedException, IndexingException {
        final List<?> input = Arrays.asList(rule, obj, timestamp, parentJson, guid, guidToObj,
                isPublic);
        retrier.retryCons(i -> indexObjectInStorage(i), input, null);
    }

    private void indexObjectInStorage(final List<?> input) throws FatalRetriableIndexingException {
        final ObjectTypeParsingRules rule = (ObjectTypeParsingRules) input.get(0);
        final SourceData obj = (SourceData) input.get(1);
        final Instant timestamp = (Instant) input.get(2);
        final String parentJson = (String) input.get(3);
        final GUID guid = (GUID) input.get(4);
        @SuppressWarnings("unchecked")
        final Map<GUID, ParsedObject> guidToObj = (Map<GUID, ParsedObject>) input.get(5);
        final Boolean isPublic = (Boolean) input.get(6);
        
        try {
            indexingStorage.indexObjects(rule.getGlobalObjectType(), obj,
                    timestamp, parentJson, guid, guidToObj, isPublic, rule.getIndexingRules());
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(e.getMessage(), e);
        }
    }

    private String parseObjects(
            final GUID guid,
            final ObjectLookupProvider indexLookup,
            final LinkedList<GUID> newRefPath,
            final SourceData obj,
            final ObjectTypeParsingRules rule,
            final Map<GUID, ParsedObject> guidToObj)
            throws IndexingException, InterruptedException {
        final List<?> inputs = Arrays.asList(guid, indexLookup, newRefPath, obj, rule, guidToObj);
        return retrier.retryFunc(i -> parseObjects(i), inputs, null);
    }
    
    private String parseObjects(final List<?> inputs)
            throws IndexingException, FatalRetriableIndexingException, InterruptedException {
        // should really wrap these in a class, but meh for now
        final GUID guid = (GUID) inputs.get(0);
        final ObjectLookupProvider indexLookup = (ObjectLookupProvider) inputs.get(1);
        @SuppressWarnings("unchecked")
        final List<GUID> newRefPath = (List<GUID>) inputs.get(2);
        final SourceData obj = (SourceData) inputs.get(3);
        final ObjectTypeParsingRules rule = (ObjectTypeParsingRules) inputs.get(4);
        @SuppressWarnings("unchecked")
        final Map<GUID, ParsedObject> guidToObj = (Map<GUID, ParsedObject>) inputs.get(5);
        
        final String parentJson;
        try {
            try (JsonParser jts = obj.getData().getPlacedStream()) {
                parentJson = ObjectParser.extractParentFragment(rule, jts);
            }
            final Map<GUID, String> guidToJson = ObjectParser.parseSubObjects(
                    obj, guid, rule);
            for (final GUID subGuid : guidToJson.keySet()) {
                final String json = guidToJson.get(subGuid);
                guidToObj.put(subGuid, KeywordParser.extractKeywords(
                        rule.getGlobalObjectType(), json, parentJson,
                        rule.getIndexingRules(), indexLookup, newRefPath));
            }
            /* any errors here are due to file IO or parse exceptions.
             * Parse exceptions are def not retriable
             * File IO problems are generally going to mean something is very wrong
             * (like bad disk), since the file should already exist at this point.
             */
        } catch (ObjectParseException e) {
            throw new UnprocessableEventIndexingException(e.getMessage(), e);
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(e.getMessage(), e);
        }
        return parentJson;
    }
    
//    public void share(GUID guid, int accessGroupId) throws IOException {
//        indexingStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId, 
//                false);
//    }
    
    public void undeleteAllVersions(final GUID guid) throws IOException {
        indexingStorage.undeleteAllVersions(guid);
    }

//    public void unshare(GUID guid, int accessGroupId) throws IOException {
//        indexingStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId);
//    }

    public void deleteAllVersions(final GUID guid) throws IOException {
        indexingStorage.deleteAllVersions(guid);
    }

    public void publish(GUID guid) throws IOException {
        indexingStorage.publishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    public void publishAllVersions(final GUID guid) throws IOException {
        indexingStorage.publishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }

    public void unpublish(GUID guid) throws IOException {
        indexingStorage.unpublishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    public void unpublishAllVersions(final GUID guid) throws IOException {
        indexingStorage.unpublishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }
    
    private void renameAllVersions(final GUID guid, final String newName) throws IOException {
        indexingStorage.setNameOnAllObjectVersions(guid, newName);
    }

    public IndexingStorage getIndexingStorage(String objectType) {
        return indexingStorage;
    }
    
    private class MOPLookupProvider implements ObjectLookupProvider {
        // storage code -> full ref path -> resolved guid
        private Map<String, Map<String, GUID>> refResolvingCache = new LinkedHashMap<>();
        private Map<GUID, kbasesearchengine.search.ObjectData> objLookupCache =
                new LinkedHashMap<>();
        private Map<GUID, String> guidToTypeCache = new LinkedHashMap<>();
        
        @Override
        public Set<GUID> resolveRefs(List<GUID> callerRefPath, Set<GUID> refs)
                throws IndexingException, InterruptedException {
            /* the caller ref path 1) ensures that the object refs are valid when checked against
             * the source, and 2) allows getting deleted objects with incoming references 
             * in the case of the workspace
             */
            
            // there may be a way to cache more of this info and call the workspace less
            // by checking the ref against the refs in the parent object.
            // doing it the dumb way for now.
            final EventHandler eh = getEventHandler(callerRefPath.get(0));
            final String storageCode = eh.getStorageCode();
            if (!refResolvingCache.containsKey(storageCode)) {
                refResolvingCache.put(storageCode, new HashMap<>());
            }
            final Map<GUID, String> refToRefPath = eh.buildReferencePaths(callerRefPath, refs);
            Set<GUID> ret = new LinkedHashSet<>();
            Set<GUID> refsToResolve = new LinkedHashSet<>();
            for (final GUID ref : refs) {
                final String refpath = refToRefPath.get(ref);
                if (refResolvingCache.get(storageCode).containsKey(refpath)) {
                    ret.add(refResolvingCache.get(storageCode).get(refpath));
                } else {
                    refsToResolve.add(ref);
                }
            }
            if (refsToResolve.size() > 0) {
                final Set<ResolvedReference> resrefs =
                        resolveReferences(eh, callerRefPath, refsToResolve);
                for (final ResolvedReference rr: resrefs) {
                    final GUID guid = rr.getResolvedReference();
                    final boolean indexed = retrier.retryFunc(
                            g -> checkParentGuidExists(g), guid, null);
                    if (!indexed) {
                        indexObjectWrapperFn(guid, rr.getType(), rr.getTimestamp(), false,
                                this, callerRefPath);
                    }
                    ret.add(guid);
                    refResolvingCache.get(storageCode)
                            .put(refToRefPath.get(rr.getReference()), guid);
                }
            }
            return ret;
        }
        
        private boolean checkParentGuidExists(final GUID guid) throws RetriableIndexingException {
            try {
                return indexingStorage.checkParentGuidsExist(new HashSet<>(Arrays.asList(guid)))
                        .get(guid);
            } catch (IOException e) {
                throw new RetriableIndexingException(e.getMessage(), e);
            }
        }
        
        private Set<ResolvedReference> resolveReferences(
                final EventHandler eh,
                final List<GUID> callerRefPath,
                final Set<GUID> refsToResolve)
                throws IndexingException, InterruptedException {
            final List<Object> input = Arrays.asList(eh, callerRefPath, refsToResolve);
            return retrier.retryFunc(i -> resolveReferences(i), input, null);
        }
        
        private Set<ResolvedReference> resolveReferences(final List<Object> input)
                throws IndexingException, RetriableIndexingException {
            final EventHandler eh = (EventHandler) input.get(0);
            @SuppressWarnings("unchecked")
            final List<GUID> callerRefPath = (List<GUID>) input.get(1);
            @SuppressWarnings("unchecked")
            final Set<GUID> refsToResolve = (Set<GUID>) input.get(2);

            return eh.resolveReferences(callerRefPath, refsToResolve);
        }
        
        private void indexObjectWrapperFn(
                final GUID guid,
                final StorageObjectType storageObjectType,
                final Instant timestamp,
                final boolean isPublic,
                final ObjectLookupProvider indexLookup,
                final List<GUID> objectRefPath) 
                throws IndexingException, InterruptedException {
            final List<Object> input = Arrays.asList(guid, storageObjectType, timestamp, isPublic,
                    indexLookup, objectRefPath);
            retrier.retryCons(i -> indexObjectWrapperFn(i), input, null);
        }

        private void indexObjectWrapperFn(final List<Object> input)
                throws IndexingException, InterruptedException, RetriableIndexingException {
            final GUID guid = (GUID) input.get(0);
            final StorageObjectType storageObjectType = (StorageObjectType) input.get(1);
            final Instant timestamp = (Instant) input.get(2);
            final boolean isPublic = (boolean) input.get(3);
            final ObjectLookupProvider indexLookup = (ObjectLookupProvider) input.get(4);
            @SuppressWarnings("unchecked")
            final List<GUID> objectRefPath = (List<GUID>) input.get(5);
            
            indexObject(guid, storageObjectType, timestamp, isPublic, indexLookup, objectRefPath);
        }

        @Override
        public Map<GUID, kbasesearchengine.search.ObjectData> lookupObjectsByGuid(
                final Set<GUID> guids)
                throws InterruptedException, IndexingException {
            Map<GUID, kbasesearchengine.search.ObjectData> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (objLookupCache.containsKey(guid)) {
                    ret.put(guid, objLookupCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                final List<kbasesearchengine.search.ObjectData> objList =
                        retrier.retryFunc(g -> getObjectsByIds(g), guidsToLoad, null);
                Map<GUID, kbasesearchengine.search.ObjectData> loaded = 
                        objList.stream().collect(Collectors.toMap(od -> od.guid, 
                                Function.identity()));
                objLookupCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
        
        private List<kbasesearchengine.search.ObjectData> getObjectsByIds(final Set<GUID> guids)
                throws RetriableIndexingException {
            kbasesearchengine.search.PostProcessing pp = 
                    new kbasesearchengine.search.PostProcessing();
            pp.objectData = false;
            pp.objectKeys = true;
            pp.objectInfo = true;
            try {
                return indexingStorage.getObjectsByIds(guids, pp);
            } catch (IOException e) {
                throw new RetriableIndexingException(e.getMessage(), e);
            }
        }
        
        @Override
        public ObjectTypeParsingRules getTypeDescriptor(final String type)
                throws IndexingException {
            return typeStorage.getObjectType(type);
        }
        
        @Override
        public Map<GUID, String> getTypesForGuids(Set<GUID> guids)
                throws InterruptedException, IndexingException {
            Map<GUID, String> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (guidToTypeCache.containsKey(guid)) {
                    ret.put(guid, guidToTypeCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                final List<kbasesearchengine.search.ObjectData> data =
                        retrier.retryFunc(g -> getObjectsByIds(g), guidsToLoad, null);
                final Map<GUID, String> loaded = data.stream()
                        .collect(Collectors.toMap(od -> od.guid, od -> od.type));
                guidToTypeCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
    }
}
