package kbasesearchengine.events.storage;

import java.util.List;
import java.util.Set;

import com.google.common.base.Optional;

import kbasesearchengine.events.ChildStatusEvent;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventID;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StoredChildStatusEvent;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;

/** A storage system for status events generated by an external service.
 * @author gaprice@lbl.gov
 *
 */
public interface StatusEventStorage {
    
    /** The name for a default worker code that is applied to an event if it has no other worker
     * codes, or can be applied to events manually if the event should run on a worker that
     * processes events with the default code.
     */
    public static final String DEFAULT_WORKER_CODE = "default";

    /** Store a new event.
     * @param newEvent the event.
     * @param state the current processing state of the event.
     * @param workerCodes a set of codes for the event that designate the workers that may process
     * the event. If the list is null or empty the event will get the {@link #DEFAULT_WORKER_CODE}
     * code.
     * @param storedBy an arbitrary string indicating the entity that stored the event.
     * @return a stored status event.
     * @throws FatalRetriableIndexingException if an error occurs while storing the event.
     */
    StoredStatusEvent store(
            StatusEvent newEvent,
            StatusEventProcessingState state,
            Set<String> workerCodes,
            String storedBy)
            throws FatalRetriableIndexingException;
    
    /** Store a status event that resulted in an error and that is a child of another status event.
     * Child status events are immutable once stored. Note that no checking is done on the
     * validity of the parent event's ID.
     * If the error message or stack trace are long, they will be silently truncated.
     * @param newEvent the child event to store.
     * @param errorCode a 20 character or less string identifying the error type.
     * @param error the error.
     * @return the stored child event.
     */
    StoredChildStatusEvent store(
            ChildStatusEvent newEvent,
            final String errorCode,
            final Throwable error)
            throws FatalRetriableIndexingException;

    /** Get an event by its ID.
     * @param id the id.
     * @return the event or absent if the id does not exist in the storage system.
     * @throws FatalRetriableIndexingException if an error occurs while getting the event.
     */
    Optional<StoredStatusEvent> get(StatusEventID id) throws FatalRetriableIndexingException;
    
    /** Get a child event by its ID.
     * @param id the id.
     * @return the child event or absent if the id does not exist in the storage system.
     * @throws FatalRetriableIndexingException if an error occurs while getting the event.
     */
    Optional<StoredChildStatusEvent> getChild(StatusEventID id)
            throws FatalRetriableIndexingException;

    /** Get list of events, by processing state, ordered by the event timestamp such that the
     * events with the earliest timestamp are first in the list.
     * @param state the processing state of the events to be returned.
     * @param limit the maximum number of events to return. If < 1 or > 10000 is set to 10000.
     * @return the list of events.
     * @throws FatalRetriableIndexingException if an error occurs while getting the events.
     */
    List<StoredStatusEvent> get(StatusEventProcessingState state, int limit)
            throws FatalRetriableIndexingException;

    /** Simultaneously find an event with a particular processing state and set a new state.
     * This is often used to switch an event from {@link StatusEventProcessingState#READY} to
     * {@link StatusEventProcessingState#PROC}.
     * Returns the event that matches the processing state and worker codes with the earliest
     * timestamp.
     * @param oldState the state of the event to find.
     * @param workerCodes the permissible worker codes for the event. A null or empty list
     * implies the default code.
     * @param newState the state to which the event will be updated.
     * @param updater an optional (e.g. nullable) id or name to associate with the state change.
     * Only the most recent state change is recorded.
     * @return the updated event or absent if no events are in the requested state.
     * @throws FatalRetriableIndexingException
     */
    Optional<StoredStatusEvent> setAndGetProcessingState(
            StatusEventProcessingState oldState,
            Set<String> workerCodes,
            StatusEventProcessingState newState,
            String updater)
            throws FatalRetriableIndexingException;
    
    /** Mark an event with a processing state.
     * @param id the id of the event to modify.
     * @param oldState the expected state of the event. If non-null, an event is only modified
     * if both the id and the oldState match.
     * @param newState the processing state to set on the event.
     * @return true if the event was updated, false if the event was not found in the storage
     * system.
     * @throws FatalRetriableIndexingException if an error occurs while setting the state.
     */
    boolean setProcessingState(
            StatusEventID id,
            StatusEventProcessingState oldState,
            StatusEventProcessingState newState)
            throws FatalRetriableIndexingException;


    /**
     * Resets all failed events simply by setting their state from FAIL to
     * UNPROC.
     */
    void resetFailedEvents()
            throws FatalRetriableIndexingException;


    /** Mark an event as a {@link StatusEventProcessingState.FAIL} with error information.
     * If the error message or stack trace are long, they will be silently truncated.
     * @param id the id of the event to modify.
     * @param oldState the expected state of the event. If non-null, an event is only modified
     * if both the id and the oldState match.
     * @param errorCode a 20 character or less string identifying the error type.
     * @param error the error.
     * @return true if the event was updated, false if the event was not found in the storage
     * system.
     * @throws FatalRetriableIndexingException if an error occurs while setting the state.
     */
    boolean setProcessingState(
            final StatusEventID id,
            final StatusEventProcessingState oldState,
            final String errorCode,
            final Throwable error)
            throws FatalRetriableIndexingException;
}