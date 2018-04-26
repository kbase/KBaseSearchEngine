package kbasesearchengine.test.events.exceptions;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static kbasesearchengine.test.common.TestCommon.assertCloseMS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import com.google.common.base.Optional;

import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventID;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StatusEventType;
import kbasesearchengine.events.StatusEventWithId;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.ErrorType;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.Retrier;
import kbasesearchengine.events.exceptions.RetriesExceededIndexingException;
import kbasesearchengine.events.exceptions.RetryConsumer;
import kbasesearchengine.events.exceptions.RetryFunction;
import kbasesearchengine.events.exceptions.RetryLogger;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.test.common.TestCommon;

public class RetrierTest {
    
    private class LogEvent {
        private final Instant time;
        private final int retryCount;
        private final Optional<StatusEventWithId> event;
        private final RetriableIndexingException exception;
        
        private LogEvent(
                final Instant time,
                final int retryCount,
                final Optional<StatusEventWithId> optional,
                final RetriableIndexingException exception) {
            this.time = time;
            this.retryCount = retryCount;
            this.event = optional;
            this.exception = exception;
        }
    }
    
    private class CollectingLogger implements RetryLogger {

        private final List<LogEvent> events = new LinkedList<>();
        
        @Override
        public void log(int retryCount, Optional<StatusEventWithId> optional,
                RetriableIndexingException e) {
            events.add(new LogEvent(Instant.now(), retryCount, optional, e));
        }
        
    }

    @Test
    public void construct() throws Exception {
        final CollectingLogger log = new CollectingLogger();
        final Retrier ret = new Retrier(2, 10, Arrays.asList(4, 5, 6), log);
        
        assertThat("incorrect retries", ret.getRetryCount(), is(2));
        assertThat("incorrect delay", ret.getDelayMS(), is(10));
        assertThat("incorrect fatal delays", ret.getFatalRetryBackoffsMS(),
                is(Arrays.asList(4, 5, 6)));
        assertThat("incorrect logger", ret.getLogger(), is(log));
    }
    
    @Test
    public void constructFail() throws Exception {
        final CollectingLogger log = new CollectingLogger();
        failConstruct(0, 1, Collections.emptyList(), log,
                new IllegalArgumentException("retryCount must be at least 1"));
        failConstruct(1, 0, Collections.emptyList(), log,
                new IllegalArgumentException("delayMS must be at least 1"));
        failConstruct(1, 1, null, log, new NullPointerException("fatalRetryBackoffsMS"));
        failConstruct(1, 1, Arrays.asList(1, null), log,
                new IllegalArgumentException("Illegal value in fatalRetryBackoffsMS: null"));
        failConstruct(1, 1, Arrays.asList(1, 0), log,
                new IllegalArgumentException("Illegal value in fatalRetryBackoffsMS: 0"));
        failConstruct(1, 1, Collections.emptyList(), null, new NullPointerException("logger"));
    }

    private void failConstruct(
            final int retries,
            final int retryDelay,
            final List<Integer> fatalRetryDelay,
            final RetryLogger logger,
            final Exception expected) {
        try {
            new Retrier(retries, retryDelay, fatalRetryDelay, logger);
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }
    
    @Test
    public void immutableFatalDelayList() {
        final List<Integer> delays = new ArrayList<>();
        delays.add(1);
        delays.add(2);
        delays.add(3);
        final Retrier ret = new Retrier(2, 10, delays, new CollectingLogger());
        try {
            ret.getFatalRetryBackoffsMS().set(2, 42);
        } catch (UnsupportedOperationException e) {
            // test passed
        }
    }
    
    private class TestConsumer<T> implements RetryConsumer<T> {

        private final T input;
        private final int retries;
        private int count = 0;
        private final boolean fatal;
        private final ErrorType errorType;
        
        private TestConsumer(T input, int retries, ErrorType errorType) {
            this.input = input;
            this.retries = retries;
            fatal = false;
            this.errorType = errorType;
        }
        
        private TestConsumer(T input, int retries, ErrorType errorType, boolean fatal) {
            this.input = input;
            this.retries = retries;
            this.fatal = fatal;
            this.errorType = errorType;
        }

        @Override
        public void accept(final T t)
                throws IndexingException, RetriableIndexingException, InterruptedException {
            assertThat("incorrect input", t, is(input));
            if (count == retries) {
                return;
            } else {
                count++;
                if (fatal) {
                    throw new FatalRetriableIndexingException(errorType, "game over man");
                } else {
                    throw new RetriableIndexingException(errorType, "bar");
                }
            }
        }
        
    }
    
    @Test
    public void consumer1RetrySuccessNoEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(1, 100, Collections.emptyList(), collog);
        final Instant start = Instant.now();
        ret.retryCons(new TestConsumer<>("foo", 1, ErrorType.LOCATION_ERROR), "foo", null);
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(1));
        final LogEvent le = collog.events.get(0);
        assertThat("incorrect retry count", le.retryCount, is(1));
        assertThat("incorrect event", le.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le.exception, new RetriableIndexingException(
                ErrorType.LOCATION_ERROR, "bar"));
        assertCloseMS(start, le.time, 0, 60);
        assertCloseMS(start, end, 100, 60);
    }
    
    @Test
    public void consumer2RetrySuccessWithEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Collections.emptyList(), collog);
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("foo", "whee"), Instant.now(), StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(23)
                .withNullableObjectID("bar")
                .withNullableVersion(6)
                .build(),
                new StatusEventID("wugga"),
                StatusEventProcessingState.UNPROC)
                .build();
        final Instant start = Instant.now();
        ret.retryCons(new TestConsumer<>("foo", 2, ErrorType.INDEXING_CONFLICT), "foo", ev);
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le1.exception, new RetriableIndexingException(
                ErrorType.INDEXING_CONFLICT, "bar"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le2.exception, new RetriableIndexingException(
                ErrorType.INDEXING_CONFLICT, "bar"));
        assertCloseMS(start, le2.time, 100, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void consumerRetriesExceeded() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Collections.emptyList(), collog);
        final Instant start = Instant.now();
        try {
            ret.retryCons(new TestConsumer<>("foo", -1, ErrorType.SUBOBJECT_COUNT), "foo", null);
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new RetriesExceededIndexingException(
                    ErrorType.SUBOBJECT_COUNT, "bar"));
        }
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le1.exception, new RetriableIndexingException(
                ErrorType.SUBOBJECT_COUNT, "bar"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le2.exception, new RetriableIndexingException(
                ErrorType.SUBOBJECT_COUNT, "bar"));
        assertCloseMS(start, le2.time, 100, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void consumer1FatalRetrySuccessNoEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(1, 100, Arrays.asList(140), collog);
        final Instant start = Instant.now();
        ret.retryCons(new TestConsumer<>("foo", 1, ErrorType.OTHER, true), "foo", null);
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(1));
        final LogEvent le = collog.events.get(0);
        assertThat("incorrect retry count", le.retryCount, is(1));
        assertThat("incorrect event", le.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le.time, 0, 60);
        assertCloseMS(start, end, 140, 60);
    }
    
    @Test
    public void consumer2FatalRetrySuccessWithEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Arrays.asList(140, 60), collog);
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("foo", "whee"), Instant.now(), StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(23)
                .withNullableObjectID("bar")
                .withNullableVersion(6)
                .build(),
                new StatusEventID("wugga"),
                StatusEventProcessingState.UNPROC)
                .build();
        final Instant start = Instant.now();
        ret.retryCons(new TestConsumer<>("foo", 2, ErrorType.OTHER, true), "foo", ev);
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le1.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le2.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le2.time, 140, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void consumerFatalRetriesExceeded() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Arrays.asList(60, 140), collog);
        final Instant start = Instant.now();
        try {
            ret.retryCons(new TestConsumer<>("foo", -1, ErrorType.OTHER, true), "foo", null);
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new FatalIndexingException(
                    ErrorType.OTHER, "game over man"));
        }
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le1.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le2.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le2.time, 60, 60);
        assertCloseMS(start, end, 200, 60);
    }

    private class TestFunction<T, R> implements RetryFunction<T, R> {

        private final T input;
        private final R ret;
        private final int retries;
        private int count = 0;
        private final boolean fatal;
        private final ErrorType errorType;
        
        private TestFunction(T input, R ret, int retries, ErrorType errorType) {
            this.input = input;
            this.ret = ret;
            this.retries = retries;
            fatal = false;
            this.errorType = errorType;
        }
        
        private TestFunction(
                T input,
                R ret,
                int retries,
                ErrorType errorType,
                final boolean fatal) {
            this.input = input;
            this.ret = ret;
            this.retries = retries;
            this.fatal = fatal;
            this.errorType = errorType;
        }

        @Override
        public R apply(final T t)
                throws IndexingException, RetriableIndexingException, InterruptedException {
            assertThat("incorrect input", t, is(input));
            if (count == retries) {
                return ret;
            } else {
                count++;
                if (fatal) {
                    throw new FatalRetriableIndexingException(errorType, "game over man");
                } else {
                    throw new RetriableIndexingException(errorType, "bar");
                }
            }
        }
    }

    @Test
    public void function1RetrySuccessNoEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(1, 100, Collections.emptyList(), collog);
        final Instant start = Instant.now();
        final long result = ret.retryFunc(new TestFunction<>(
                "foo", 24L, 1, ErrorType.LOCATION_ERROR), "foo", null);
        final Instant end = Instant.now();
        
        assertThat("incorrect result", result, is(24L));
        assertThat("incorrect retries", collog.events.size(), is(1));
        final LogEvent le = collog.events.get(0);
        assertThat("incorrect retry count", le.retryCount, is(1));
        assertThat("incorrect event", le.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le.exception, new RetriableIndexingException(
                ErrorType.LOCATION_ERROR, "bar"));
        assertCloseMS(start, le.time, 0, 60);
        assertCloseMS(start, end, 100, 60);
    }
    
    @Test
    public void function2RetrySuccessWithEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Collections.emptyList(), collog);
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("foo", "whee"), Instant.now(), StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(23)
                .withNullableObjectID("bar")
                .withNullableVersion(6)
                .build(),
                new StatusEventID("wugga"),
                StatusEventProcessingState.UNPROC)
                .build();
        final Instant start = Instant.now();
        final long result = ret.retryFunc(new TestFunction<>(
                "foo", 26L, 2, ErrorType.INDEXING_CONFLICT), "foo", ev);
        final Instant end = Instant.now();
        
        assertThat("incorrect result", result, is(26L));
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le1.exception, new RetriableIndexingException(
                ErrorType.INDEXING_CONFLICT, "bar"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le2.exception, new RetriableIndexingException(
                ErrorType.INDEXING_CONFLICT, "bar"));
        assertCloseMS(start, le2.time, 100, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void functionRetriesExceeded() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Collections.emptyList(), collog);
        final Instant start = Instant.now();
        try {
            ret.retryFunc(new TestFunction<>(
                    "foo", 3L, -1, ErrorType.SUBOBJECT_COUNT), "foo", null);
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new RetriesExceededIndexingException(
                    ErrorType.SUBOBJECT_COUNT, "bar"));
        }
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le1.exception, new RetriableIndexingException(
                ErrorType.SUBOBJECT_COUNT, "bar"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le2.exception, new RetriableIndexingException(
                ErrorType.SUBOBJECT_COUNT, "bar"));
        assertCloseMS(start, le2.time, 100, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void function1FatalRetrySuccessNoEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(1, 100, Arrays.asList(140), collog);
        final Instant start = Instant.now();
        final long result = ret.retryFunc(new TestFunction<>(
                "foo", 42L, 1, ErrorType.OTHER, true), "foo", null);
        final Instant end = Instant.now();
        
        assertThat("incorrect result", result, is(42L));
        assertThat("incorrect retries", collog.events.size(), is(1));
        final LogEvent le = collog.events.get(0);
        assertThat("incorrect retry count", le.retryCount, is(1));
        assertThat("incorrect event", le.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le.time, 0, 60);
        assertCloseMS(start, end, 140, 60);
    }
    
    @Test
    public void function2FatalRetrySuccessWithEvent() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Arrays.asList(140, 60), collog);
        final StoredStatusEvent ev = StoredStatusEvent.getBuilder(StatusEvent.getBuilder(
                new StorageObjectType("foo", "whee"), Instant.now(), StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(23)
                .withNullableObjectID("bar")
                .withNullableVersion(6)
                .build(),
                new StatusEventID("wugga"),
                StatusEventProcessingState.UNPROC)
                .build();
        final Instant start = Instant.now();
        final long result = ret.retryFunc(new TestFunction<>(
                "foo", 64L, 2, ErrorType.OTHER, true), "foo", ev);
        final Instant end = Instant.now();
        
        assertThat("incorrect result", result, is(64L));
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le1.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.of(ev)));
        TestCommon.assertExceptionCorrect(le2.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le2.time, 140, 60);
        assertCloseMS(start, end, 200, 60);
    }
    
    @Test
    public void functionFatalRetriesExceeded() throws Exception {
        final CollectingLogger collog = new CollectingLogger();
        final Retrier ret = new Retrier(2, 100, Arrays.asList(60, 140), collog);
        final Instant start = Instant.now();
        try {
            ret.retryFunc(new TestFunction<>("foo", 43L, -1, ErrorType.OTHER, true), "foo", null);
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new FatalIndexingException(
                    ErrorType.OTHER, "game over man"));
        }
        final Instant end = Instant.now();
        
        assertThat("incorrect retries", collog.events.size(), is(2));
        final LogEvent le1 = collog.events.get(0);
        assertThat("incorrect retry count", le1.retryCount, is(1));
        assertThat("incorrect event", le1.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le1.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));
        assertCloseMS(start, le1.time, 0, 60);
        
        final LogEvent le2 = collog.events.get(1);
        assertThat("incorrect retry count", le2.retryCount, is(2));
        assertThat("incorrect event", le2.event, is(Optional.absent()));
        TestCommon.assertExceptionCorrect(le2.exception,
                new FatalRetriableIndexingException(ErrorType.OTHER, "game over man"));

        assertCloseMS(start, le2.time, 60, 60);
        assertCloseMS(start, end, 200, 60);
    }

}
