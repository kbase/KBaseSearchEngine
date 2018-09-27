package kbasesearchengine.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import kbasesearchengine.AccessFilter;
import kbasesearchengine.GetObjectsInput;
import kbasesearchengine.GetObjectsOutput;
import kbasesearchengine.KeyDescription;
import kbasesearchengine.MatchFilter;
import kbasesearchengine.MatchValue;
import kbasesearchengine.Pagination;
import kbasesearchengine.PostProcessing;
import kbasesearchengine.SearchObjectsInput;
import kbasesearchengine.SearchObjectsOutput;
import kbasesearchengine.SearchTypesInput;
import kbasesearchengine.SearchTypesOutput;
import kbasesearchengine.SortingRule;
import kbasesearchengine.TypeDescriptor;
import kbasesearchengine.authorization.AccessGroupProvider;
import kbasesearchengine.common.GUID;
import kbasesearchengine.search.FoundHits;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.search.MatchFilter.Builder;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.TypeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.kbase.common.service.UObject;

public class SearchMethods implements SearchInterface {
    
    private final AccessGroupProvider accessGroupProvider;
    private final TypeStorage typeStorage;
    private final IndexingStorage indexingStorage;
    private final Set<String> admins;
    
    public SearchMethods(
            final AccessGroupProvider accessGroupProvider,
            final IndexingStorage indexingStorage,
            final TypeStorage typeStorage,
            final Set<String> admins) {
        this.admins = admins == null ? Collections.emptySet() : admins;
        this.accessGroupProvider = accessGroupProvider;
        this.typeStorage = typeStorage;
        this.indexingStorage = indexingStorage;
    }

    /**
     * Changes Long to Boolean. 1L = true. Anything else is False. Accepts default value
     * @param value       Intended value
     * @param defaultRet  Default boolean
     * @return
     */
    private static boolean toBool(Long value, boolean defaultRet) {
        if (value == null) {
            return defaultRet;
        }
        return value == 1L;
    }

    private static Integer toInteger(Long value) {
        return value == null ? null : (int)(long)value;
    }

    private kbasesearchengine.search.MatchValue toSearch(MatchValue mv, String source) {
        if (mv == null) {
            return null;
        }
        if (mv.getValue() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getValue());
        }
        if (mv.getIntValue() != null) {
            return new kbasesearchengine.search.MatchValue(toInteger(mv.getIntValue()));
        }
        if (mv.getDoubleValue() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getDoubleValue());
        }
        if (mv.getBoolValue() != null) {
            return new kbasesearchengine.search.MatchValue(toBool(mv.getBoolValue(), false));
        }
        if (mv.getMinInt() != null || mv.getMaxInt() != null) {
            return new kbasesearchengine.search.MatchValue(toInteger(mv.getMinInt()),
                    toInteger(mv.getMaxInt()));
        }
        if (mv.getMinDate() != null || mv.getMaxDate() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getMinDate(), mv.getMaxDate());
        }
        if (mv.getMinDouble() != null || mv.getMaxDouble() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getMinDouble(), mv.getMaxDouble());
        }
        throw new IllegalStateException("Unsupported " + source + " filter: " + mv);
    }
    
    private kbasesearchengine.search.MatchFilter toSearch(MatchFilter mf) {
        final Builder ret = 
                kbasesearchengine.search.MatchFilter.getBuilder()
                .withNullableFullTextInAll(mf.getFullTextInAll())
                .withNullableObjectName(mf.getObjectName())
                .withNullableTimestamp(toSearch(mf.getTimestamp(), "timestamp"))
                .withExcludeSubObjects(toBool(mf.getExcludeSubobjects(), false))
                .withIsSourceTagsBlackList(toBool(mf.getSourceTagsBlacklist(), false));
        if (mf.getSourceTags() != null) {
            for (final String tag: mf.getSourceTags()) {
                ret.withSourceTag(tag);
            }
        }
        if (mf.getLookupInKeys() != null) {
            for (final String key : mf.getLookupInKeys().keySet()) {
                //TODO CODE proper error for null value
                ret.withLookupInKey(key, toSearch(mf.getLookupInKeys().get(key), key));
            }
        }
        return ret.build();
    }

    /**
     * Modifies AccessFilter to kbasesearchengine.search.AccessFilter. Requested access is changed if user does not have access.
     * If user is null (unauth user), wihtPrivate is set to false and withPublic is set to true
     * @param af   requested Accessfilter
     * @param user username
     * @return kbasesearchengine.search.AccessFilter
     * @throws IOException
     * @throws IllegalArgumentException
     */
    private kbasesearchengine.search.AccessFilter toSearch(AccessFilter af, String user)
            throws IOException, IllegalArgumentException {
        List<Integer> accessGroupIds;
        final boolean withPublic = (user == null) ? true : toBool(af.getWithPublic(), false);
        final boolean withPrivate = (user == null) ? false : toBool(af.getWithPrivate(), true);
        
        if (!withPublic && !withPrivate) {
            throw new IllegalArgumentException("with_public and with_private cannot both be set to false");
        }

        accessGroupIds = withPrivate ?  accessGroupProvider.findAccessGroupIds(user) : Collections.emptyList();

        return new kbasesearchengine.search.AccessFilter()
                .withPublic(withPublic)
                .withAllHistory(toBool(af.getWithAllHistory(), false))
                .withAccessGroups(new LinkedHashSet<>(accessGroupIds))
                .withAdmin(admins.contains(user));
    }
    
    private kbasesearchengine.search.SortingRule toSearch(final SortingRule sr) {
        if (sr == null) {
            return null;
        }
        final kbasesearchengine.search.SortingRule.Builder b;
        //TODO CODE make an enum of valid standard property field names and check against input
        if (toBool(sr.getIsObjectProperty(), true)) {
            b = kbasesearchengine.search.SortingRule.getKeyPropertyBuilder(sr.getProperty());
        } else {
            b = kbasesearchengine.search.SortingRule.getStandardPropertyBuilder(sr.getProperty());
        }
        return b.withNullableIsAscending(toBool(sr.getAscending(), true)).build();
    }

    private SortingRule fromSearch(final kbasesearchengine.search.SortingRule sr) {
        if (sr == null) {
            return null;
        }
        final SortingRule ret = new SortingRule();
        if (sr.isKeyProperty()) {
            ret.withProperty(sr.getKeyProperty().get());
            ret.withIsObjectProperty(1L);
        } else {
            ret.withProperty(sr.getStandardProperty().get());
            ret.withIsObjectProperty(0L);
        }
        ret.withAscending(sr.isAscending() ? 1L : 0L);
        return ret;
    }

    private kbasesearchengine.search.Pagination toSearch(Pagination pg) {
        return pg == null ? null : new kbasesearchengine.search.Pagination(
                toInteger(pg.getStart()), toInteger(pg.getCount()));
    }

    private Pagination fromSearch(kbasesearchengine.search.Pagination pg) {
        return pg == null ? null : new Pagination().withStart((long)pg.start)
                .withCount((long)pg.count);
    }

    private kbasesearchengine.search.PostProcessing toSearch(PostProcessing pp) {
        kbasesearchengine.search.PostProcessing ret = 
                new kbasesearchengine.search.PostProcessing();
        if (pp == null) {
            ret.objectData = true;
            ret.objectKeys = true;
            ret.objectHighlight = false;
        } else {
            boolean idsOnly = toBool(pp.getIdsOnly(), false);
            ret.objectData = !(toBool(pp.getSkipData(), false) || idsOnly);
            ret.objectKeys = !(toBool(pp.getSkipKeys(), false) || idsOnly);
            //default to false currently b/c of search tags. TODO: add search tags to black list?
            ret.objectHighlight = toBool(pp.getIncludeHighlight(), false) && !idsOnly;
        }
        return ret;
    }
    
    private kbasesearchengine.ObjectData fromSearch(
            final kbasesearchengine.search.ObjectData od) {
        final kbasesearchengine.ObjectData ret = new kbasesearchengine.ObjectData();
        ret.withGuid(od.getGUID().toString());
        if (od.getParentGUID().isPresent()) {
            ret.withParentGuid(od.getParentGUID().get().toString());
        }
        if (od.getTimestamp().isPresent()) {
            ret.withTimestamp(od.getTimestamp().get().toEpochMilli());
        }
        if (od.getData().isPresent()) {
            ret.withData(new UObject(od.getData().get()));
        }
        if (od.getParentData().isPresent()) {
            ret.withParentData(new UObject(od.getParentData().get()));
        }

        ret.withObjectName(od.getObjectName().orNull());
        ret.withKeyProps(od.getKeyProperties());
        ret.withHighlight(od.getHighlight());
        
        ret.withType(od.getType().getType());
        ret.withTypeVer((long) od.getType().getVersion());

        ret.withCreator(od.getCreator().orNull());
        ret.withCopier(od.getCopier().orNull());
        ret.withMod(od.getModule().orNull());
        ret.withMethod(od.getMethod().orNull());
        ret.withModuleVer(od.getModuleVersion().orNull());
        ret.withCommit(od.getCommitHash().orNull());
        
        return ret;
    }
    
    @Override
    public SearchTypesOutput searchTypes(SearchTypesInput params, String user) throws Exception {
        long t1 = System.currentTimeMillis();
        kbasesearchengine.search.MatchFilter matchFilter = toSearch(params.getMatchFilter());
        kbasesearchengine.search.AccessFilter accessFilter = toSearch(params.getAccessFilter(), user);
        Map<String, Integer> ret = indexingStorage.searchTypes(matchFilter, accessFilter);
        return new SearchTypesOutput().withTypeToCount(ret.keySet().stream().collect(
                Collectors.toMap(Function.identity(), c -> (long)(int)ret.get(c))))
                .withSearchTime(System.currentTimeMillis() - t1);
    }
    
    @Override
    public SearchObjectsOutput searchObjects(SearchObjectsInput params, String user)
            throws Exception {

        long t1 = System.currentTimeMillis();

        // validate input
        if (params.getObjectTypes() == null) {
            params.setObjectTypes(ImmutableList.of());
        }

        kbasesearchengine.search.MatchFilter matchFilter = toSearch(params.getMatchFilter());
        List<kbasesearchengine.search.SortingRule> sorting = null;
        if (params.getSortingRules() != null) {
            sorting = params.getSortingRules().stream().map(this::toSearch).collect(
                    Collectors.toList());
        }
        kbasesearchengine.search.AccessFilter accessFilter = toSearch(params.getAccessFilter(), user);
        kbasesearchengine.search.Pagination pagination = toSearch(params.getPagination());
        kbasesearchengine.search.PostProcessing postProcessing = 
                toSearch(params.getPostProcessing());
        FoundHits hits = indexingStorage.searchObjects(params.getObjectTypes(),
                matchFilter, sorting, accessFilter, pagination, postProcessing);
        SearchObjectsOutput ret = new SearchObjectsOutput();
        ret.withPagination(fromSearch(hits.pagination));
        ret.withSortingRules(hits.sortingRules.stream().map(this::fromSearch).collect(
                Collectors.toList()));
        if (hits.objects == null) {
            ret.withObjects(hits.guids.stream().map(guid -> new kbasesearchengine.ObjectData().
                    withGuid(guid.toString())).collect(Collectors.toList()));
        } else {
            ret.withObjects(hits.objects.stream().map(this::fromSearch).collect(
                    Collectors.toList()));
        }
        ret.withTotal((long)hits.total);
        ret.withSearchTime(System.currentTimeMillis() - t1);

        getLogger().info("user: {}", user );
        getLogger().info("query: {}, with postProcessing: {}", params.getMatchFilter().toString(), params.getPostProcessing().toString() );
        getLogger().info("Number of hits returned: {}", ret.getTotal());


        return ret;
    }

    @Override
    public GetObjectsOutput getObjects(final GetObjectsInput params, final String user)
            throws Exception {
        final Set<Integer> accessGroupIDs = new HashSet<>(accessGroupProvider.findAccessGroupIds(user));
        final long t1 = System.currentTimeMillis();

        final Set<GUID> guids = new LinkedHashSet<>();
        for (final String guid : params.getGuids()) {
            final GUID g = new GUID(guid);

            //TODO DP this is a quick fix for now, doesn't take data palettes into account
            if (accessGroupIDs.contains(g.getAccessGroupId())) {
                // don't throw an error, just don't return data
                guids.add(g);
            }
        }

        final kbasesearchengine.search.PostProcessing postProcessing =
                toSearch(params.getPostProcessing());
        final List<kbasesearchengine.search.ObjectData> objs = indexingStorage.getObjectsByIds(
                guids, postProcessing);
        final GetObjectsOutput ret = new GetObjectsOutput().withObjects(objs.stream()
                .map(this::fromSearch).collect(Collectors.toList()));
        ret.withSearchTime(System.currentTimeMillis() - t1);
        return ret;
    }

    @Override
    public Map<String, TypeDescriptor> listTypes(String uniqueType) throws Exception {
        //TODO VERS remove keys from TypeDescriptor, document that listObjectTypes only returns the most recent version of each type
        Map<String, TypeDescriptor> ret = new LinkedHashMap<>();
        for (ObjectTypeParsingRules otpr : typeStorage.listObjectTypeParsingRules()) {
            String typeName = otpr.getGlobalObjectType().getType();
            if (uniqueType != null && !uniqueType.equals(typeName)) {
                continue;
            }
            String uiTypeName = otpr.getUiTypeName();
            if (uiTypeName == null) {
                uiTypeName = guessUIName(typeName);
            }
            List<KeyDescription> keys = new ArrayList<>();
            for (IndexingRules ir : otpr.getIndexingRules()) {
                if (ir.isNotIndexed()) {
                    continue;
                }
                String keyName = ir.getKeyName();
                String uiKeyName = ir.getUiName();
                String keyValueType = ir.getKeywordType().orNull();
                if (keyValueType == null) {
                    keyValueType = "string"; //TODO CODE this seems wrong for fulltext, which is the only case where keyWordtype is null
                }
                long hidden = ir.isUiHidden() ? 1L : 0L;
                KeyDescription kd = new KeyDescription().withKeyName(keyName)
                        .withKeyUiTitle(uiKeyName).withKeyValueType(keyValueType)
                        .withKeyValueType(keyValueType).withHidden(hidden)
                        .withLinkKey(ir.getUiLinkKey().orNull());
                keys.add(kd);
            }
            TypeDescriptor td = new TypeDescriptor().withTypeName(typeName)
                    .withTypeUiTitle(uiTypeName).withKeys(keys);
            ret.put(typeName, td);
        }
        return ret;
    }

    private Logger getLogger() {
        return LoggerFactory.getLogger(NarrativeInfoDecorator.class);
    }

    private static String guessUIName(String id) {
        return id.substring(0, 1).toUpperCase() + id.substring(1);
    }
}
