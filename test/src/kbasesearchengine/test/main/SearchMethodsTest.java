package kbasesearchengine.test.main;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static kbasesearchengine.test.common.TestCommon.set;

import java.util.Arrays;
import java.util.Collections;

import kbasesearchengine.MatchFilter;
import org.junit.Test;

import kbasesearchengine.authorization.AccessGroupProvider;
import kbasesearchengine.main.SearchInterface;
import kbasesearchengine.main.SearchMethods;
import kbasesearchengine.search.FoundHits;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.search.PostProcessing;
import kbasesearchengine.search.SortingRule;
import kbasesearchengine.system.TypeStorage;
import kbasesearchengine.SearchObjectsInput;
import kbasesearchengine.SearchObjectsOutput;
import kbasesearchengine.SearchTypesInput;
import kbasesearchengine.SearchTypesOutput;
import kbasesearchengine.AccessFilter;
import kbasesearchengine.Pagination;
import kbasesearchengine.test.common.TestCommon;

public class SearchMethodsTest {
    
    private final static PostProcessing PP_DEFAULT = new PostProcessing();
    static {
        PP_DEFAULT.objectData = true;
        PP_DEFAULT.objectInfo = true;
        PP_DEFAULT.objectKeys = true;
    }

    //TODO TEST add a whole lot more tests. Most of SearchMethods is not covered.
    
    @Test
    public void searchObjectsExcludeSubObjects() throws Exception {
        // false cases
        searchObjectsCheckMatchFilter(
                new MatchFilter(),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchObjectsCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(null),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchObjectsCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(0L),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchObjectsCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(2L),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        
        //true case
        searchObjectsCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(1L),
                kbasesearchengine.search.MatchFilter.getBuilder().withExcludeSubObjects(true)
                        .build());
    }
    
    @Test
    public void searchObjectsSourceTags() throws Exception {
        // null cases
        searchObjectsCheckMatchFilter(
                new MatchFilter(),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchObjectsCheckMatchFilter(
                new MatchFilter().withSourceTags(null).withSourceTagsBlacklist(null),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        
        // whitelist
        searchObjectsCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar")),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .build());
        
        // explicit whitelist
        searchObjectsCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar"))
                        .withSourceTagsBlacklist(0L),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .build());
        
        // blacklist
        searchObjectsCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar"))
                        .withSourceTagsBlacklist(1L),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .withIsSourceTagsBlackList(true)
                        .build());
    }
    
    @Test
    public void searchObjectsIllegalSourceTag() {
        failSearchObjectsSourceTag(null, new IllegalArgumentException(
                "sourceTag cannot be null or whitespace only"));
        failSearchObjectsSourceTag("  \t  \n  ", new IllegalArgumentException(
                "sourceTag cannot be null or whitespace only"));
    }
    
    private void failSearchObjectsSourceTag(final String tag, final Exception expected) {
        try {
            searchObjectsCheckMatchFilter(
                    new MatchFilter().withSourceTags(Arrays.asList("foo", tag)),
                    kbasesearchengine.search.MatchFilter.getBuilder().build());
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
        
    }

    private void searchObjectsCheckMatchFilter(
            final MatchFilter input,
            final kbasesearchengine.search.MatchFilter expected)
            throws Exception {
        final AccessGroupProvider agp = mock(AccessGroupProvider.class);
        final IndexingStorage is = mock(IndexingStorage.class);
        final TypeStorage ts = mock(TypeStorage.class);

        final SearchInterface sm = new SearchMethods(agp, is, ts, Collections.emptySet());
        
        // what's returned doesn't matter, we're just checking that indexing storage gets the
        // right message
        
        final SortingRule sr = new SortingRule();
        sr.isTimestamp = true;
        sr.ascending = true;
        
        final FoundHits fh = new FoundHits();
        fh.pagination = null;
        fh.sortingRules = Arrays.asList(sr);
        fh.total = 1;
        fh.guids = set();
        fh.objects = Collections.emptyList();
        
        when(is.searchObjects(
                Arrays.asList("Genome"),
                expected,
                null, // sort
                new kbasesearchengine.search.AccessFilter().withAccessGroups(set()),
                null, // pagination
                PP_DEFAULT))
                .thenReturn(fh);
        
        final SearchObjectsOutput res = sm.searchObjects(new SearchObjectsInput()
                .withObjectTypes(Arrays.asList("Genome"))
                .withMatchFilter(input)
                .withAccessFilter(new AccessFilter()),
                "auser");

        assertThat("incorrect objects", res.getObjects(), is(Collections.emptyList()));
        assertThat("incorrect pagination", res.getPagination(), is((Pagination) null));
        // if we want to check search time, need to mock a Clock. Don't bother for now.
        // assertThat("incorrect objects", res.getSearchTime(), is(20));
        // don't care about the sorting rules for this test, so just check size
        assertThat("incorrect sorting rules count", res.getSortingRules().size(), is(1));
        assertThat("incorrect total", res.getTotal(), is(1L));
    }
    
    @Test
    public void searchTypesExcludeSubObjects() throws Exception {
        // false cases
        searchTypesCheckMatchFilter(
                new MatchFilter(),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchTypesCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(null),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchTypesCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(0L),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchTypesCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(2L),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        
        //true case
        searchTypesCheckMatchFilter(
                new MatchFilter().withExcludeSubobjects(1L),
                kbasesearchengine.search.MatchFilter.getBuilder().withExcludeSubObjects(true)
                        .build());
    }
    
    @Test
    public void searchTypesSourceTags() throws Exception {
        // null cases
        searchTypesCheckMatchFilter(
                new MatchFilter(),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        searchTypesCheckMatchFilter(
                new MatchFilter().withSourceTags(null).withSourceTagsBlacklist(null),
                kbasesearchengine.search.MatchFilter.getBuilder().build());
        
        // whitelist
        searchTypesCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar")),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .build());
        
        // explicit whitelist
        searchTypesCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar"))
                        .withSourceTagsBlacklist(0L),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .build());
        
        // blacklist
        searchTypesCheckMatchFilter(
                new MatchFilter().withSourceTags(Arrays.asList("foo", "bar"))
                        .withSourceTagsBlacklist(1L),
                kbasesearchengine.search.MatchFilter.getBuilder()
                        .withSourceTag("foo")
                        .withSourceTag("bar")
                        .withIsSourceTagsBlackList(true)
                        .build());
    }
    
    @Test
    public void searchTypesIllegalSourceTag() {
        failSearchTypesSourceTag(null, new IllegalArgumentException(
                "sourceTag cannot be null or whitespace only"));
        failSearchTypesSourceTag("  \t  \n  ", new IllegalArgumentException(
                "sourceTag cannot be null or whitespace only"));
    }
    
    private void failSearchTypesSourceTag(final String tag, final Exception expected) {
        try {
            searchTypesCheckMatchFilter(
                    new MatchFilter().withSourceTags(Arrays.asList("foo", tag)),
                    kbasesearchengine.search.MatchFilter.getBuilder().build());
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
        
    }

    private void searchTypesCheckMatchFilter(
            final MatchFilter input,
            final kbasesearchengine.search.MatchFilter expected)
            throws Exception {
        final AccessGroupProvider agp = mock(AccessGroupProvider.class);
        final IndexingStorage is = mock(IndexingStorage.class);
        final TypeStorage ts = mock(TypeStorage.class);

        final SearchInterface sm = new SearchMethods(agp, is, ts, Collections.emptySet());
        
        // what's returned doesn't matter, we're just checking that indexing storage gets the
        // right message
        
        when(is.searchTypes(
                expected,
                new kbasesearchengine.search.AccessFilter().withAccessGroups(set())))
                .thenReturn(Collections.emptyMap());
        
        final SearchTypesOutput res = sm.searchTypes(new SearchTypesInput()
                .withMatchFilter(input)
                .withAccessFilter(new AccessFilter()),
                "auser");
        
        assertThat("incorrect counts", res.getTypeToCount(), is(Collections.emptyMap()));
        // if we want to check search time, need to mock a Clock. Don't bother for now.
        // assertThat("incorrect objects", res.getSearchTime(), is(20));
    }
    
}
