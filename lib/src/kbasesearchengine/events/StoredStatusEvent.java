package kbasesearchengine.events;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.google.common.base.Optional;

import kbasesearchengine.tools.Utils;

/** A status event stored in the storage system and therefore associated with an id
 * and a processing state.
 * @author gaprice@lbl.gov
 *
 */
public class StoredStatusEvent implements StatusEventWithId {
    
    private final StatusEvent event;
    private final StatusEventID id;
    private final StatusEventProcessingState state;
    private final Optional<Instant> updateTime;
    private final Optional<String> updater;
    private final Set<String> workerCodes;
    private final Optional<String> storedBy;
    private final Optional<Instant> storeTime;
    private final Optional<String> errorCode;
    private final Optional<String> errorMessage;
    private final Optional<String> errorStackTrace;
    
    private StoredStatusEvent(
            final StatusEvent event,
            final StatusEventID id,
            final StatusEventProcessingState state,
            final Optional<Instant> updateTime,
            final Optional<String> updater,
            final Set<String> workerCodes,
            final Optional<String> storedBy,
            final Optional<Instant> storeTime,
            final String errorCode,
            final String errorMessage,
            final String errorStackTrace) {
        this.event = event;
        this.id = id;
        this.state = state;
        this.updateTime = updateTime;
        this.updater = updater;
        this.workerCodes = Collections.unmodifiableSet(workerCodes);
        this.storedBy = storedBy;
        this.storeTime = storeTime;
        this.errorCode = Optional.fromNullable(errorCode);
        this.errorMessage = Optional.fromNullable(errorMessage);
        this.errorStackTrace = Optional.fromNullable(errorStackTrace);
    }

    @Override
    public StatusEvent getEvent() {
        return event;
    }

    @Override
    public StatusEventID getID() {
        return id;
    }
    
    @Override
    public boolean isParentId() {
        return false;
    }

    /** Return the state of the stored event the last time it was accessed.
     * @return the event processing state.
     */
    public StatusEventProcessingState getState() {
        return state;
    }
    
    /** Get the time at which the event was updated.
     * @return the update time, or absent if missing.
     */
    public Optional<Instant> getUpdateTime() {
        return updateTime;
    }

    /** Return the ID of the operator that last updated the processing state.
     * @return the operator ID, or absent if missing.
     */
    public Optional<String> getUpdater() {
        return updater;
    }
    
    /** Return the codes that specify which workers can process the event.
     * @return the codes.
     */
    public Set<String> getWorkerCodes() {
        return workerCodes;
    }
    
    /** Get an arbitrary string identifying the entity that stored this event, if available.
     * @return an identifier for the entity that stored the event.
     */
    public Optional<String> getStoredBy() {
        return storedBy;
    }
    
    /** Get the time that this event was stored in the storage system. Not all event sources
     * set the store time, although doing so is best practice.
     * @return the time the event was stored.
     */
    public Optional<Instant> getStoreTime() {
        return storeTime;
    }
    
    /** Get the error code for the error associated with this event, if any.
     * @return the error code.
     */
    public Optional<String> getErrorCode() {
        return errorCode;
    }

    /** Get the error message for the error associated with this event, if any.
     * @return the error message.
     */
    public Optional<String> getErrorMessage() {
        return errorMessage;
    }

    /** Get the error stack trace for the error associated with this event, if any.
     * @return the error stack trace.
     */
    public Optional<String> getErrorStackTrace() {
        return errorStackTrace;
    }
    
    @Override
    public String toString() {
        StringBuilder builder2 = new StringBuilder();
        builder2.append("StoredStatusEvent [event=");
        builder2.append(event);
        builder2.append(", id=");
        builder2.append(id);
        builder2.append(", state=");
        builder2.append(state);
        builder2.append(", updateTime=");
        builder2.append(updateTime);
        builder2.append(", updater=");
        builder2.append(updater);
        builder2.append(", workerCodes=");
        builder2.append(workerCodes);
        builder2.append(", storedBy=");
        builder2.append(storedBy);
        builder2.append(", storeTime=");
        builder2.append(storeTime);
        builder2.append(", errorCode=");
        builder2.append(errorCode);
        builder2.append(", errorMessage=");
        builder2.append(errorMessage);
        builder2.append(", errorStackTrace=");
        builder2.append(errorStackTrace);
        builder2.append("]");
        return builder2.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((errorCode == null) ? 0 : errorCode.hashCode());
        result = prime * result
                + ((errorMessage == null) ? 0 : errorMessage.hashCode());
        result = prime * result
                + ((errorStackTrace == null) ? 0 : errorStackTrace.hashCode());
        result = prime * result + ((event == null) ? 0 : event.hashCode());
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((state == null) ? 0 : state.hashCode());
        result = prime * result
                + ((storeTime == null) ? 0 : storeTime.hashCode());
        result = prime * result
                + ((storedBy == null) ? 0 : storedBy.hashCode());
        result = prime * result
                + ((updateTime == null) ? 0 : updateTime.hashCode());
        result = prime * result + ((updater == null) ? 0 : updater.hashCode());
        result = prime * result
                + ((workerCodes == null) ? 0 : workerCodes.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        StoredStatusEvent other = (StoredStatusEvent) obj;
        if (errorCode == null) {
            if (other.errorCode != null) {
                return false;
            }
        } else if (!errorCode.equals(other.errorCode)) {
            return false;
        }
        if (errorMessage == null) {
            if (other.errorMessage != null) {
                return false;
            }
        } else if (!errorMessage.equals(other.errorMessage)) {
            return false;
        }
        if (errorStackTrace == null) {
            if (other.errorStackTrace != null) {
                return false;
            }
        } else if (!errorStackTrace.equals(other.errorStackTrace)) {
            return false;
        }
        if (event == null) {
            if (other.event != null) {
                return false;
            }
        } else if (!event.equals(other.event)) {
            return false;
        }
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }
        if (state != other.state) {
            return false;
        }
        if (storeTime == null) {
            if (other.storeTime != null) {
                return false;
            }
        } else if (!storeTime.equals(other.storeTime)) {
            return false;
        }
        if (storedBy == null) {
            if (other.storedBy != null) {
                return false;
            }
        } else if (!storedBy.equals(other.storedBy)) {
            return false;
        }
        if (updateTime == null) {
            if (other.updateTime != null) {
                return false;
            }
        } else if (!updateTime.equals(other.updateTime)) {
            return false;
        }
        if (updater == null) {
            if (other.updater != null) {
                return false;
            }
        } else if (!updater.equals(other.updater)) {
            return false;
        }
        if (workerCodes == null) {
            if (other.workerCodes != null) {
                return false;
            }
        } else if (!workerCodes.equals(other.workerCodes)) {
            return false;
        }
        return true;
    }

    /** Get a builder for a {@link StoredStatusEvent}.
     * @param event the stored event.
     * @param id the ID of the event.
     * @param state the state of the event.
     * @return a new builder.
     */
    public static Builder getBuilder(
            final StatusEvent event,
            final StatusEventID id,
            final StatusEventProcessingState state) {
        return new Builder(event, id, state);
    }
    
    /** A builder for {@link StoredStatusEvent}s.
     * @author gaprice@lbl.gov
     *
     */
    public static class Builder {

        private final StatusEvent event;
        private final StatusEventID id;
        private final StatusEventProcessingState state;
        private Optional<Instant> updateTime = Optional.absent();
        private Optional<String> updater = Optional.absent();
        private Set<String> workerCodes = new HashSet<>();
        private Optional<String> storedBy = Optional.absent();
        private Optional<Instant> storeTime = Optional.absent();
        private String errorCode = null;
        private String errorMessage = null;
        private String errorStackTrace = null;
        
        private Builder(
                final StatusEvent event,
                final StatusEventID id,
                final StatusEventProcessingState state) {
            Utils.nonNull(event, "event");
            Utils.nonNull(id, "id");
            Utils.nonNull(state, "state");
            this.event = event;
            this.id = id;
            this.state = state;
        }
        
        /** Add a code that specifies which workers can process this event to the builder.
         * @param workerCode the worker code.
         * @return this builder.
         */
        public Builder withWorkerCode(final String workerCode) {
            Utils.notNullOrEmpty(workerCode, "workerCode cannot be null or whitespace");
            workerCodes.add(workerCode);
            return this;
        }
        
        /** Add an update time and optional updater id to the event.
         * @param updateTime the update time. If null, the update is wholly ignored.
         * @param updater the id of the updater. If null or whitespace the id is ignored.
         * @return this builder.
         */
        public Builder withNullableUpdate(final Instant updateTime, final String updater) {
            if (updateTime == null) {
                return this;
            }
            this.updateTime = Optional.of(updateTime);
            if (!Utils.isNullOrEmpty(updater)) {
                this.updater = Optional.of(updater);
            } else {
                this.updater = Optional.absent();
            }
            return this;
        }
        
        /** Add a string indicating the entity that stored this event in the storage system.
         * Nulls and whitespace only strings are ignored.
         * @param storedBy the entity that stored this event.
         * @return this builder.
         */
        public Builder withNullableStoredBy(final String storedBy) {
            if (!Utils.isNullOrEmpty(storedBy)) {
                this.storedBy = Optional.of(storedBy);
            }
            return this;
        }
        
        /** Add a timestamp for when the event was stored.
         * @param storeTime a timestamp.
         * @return this builder.
         */
        public Builder withNullableStoreTime(final Instant storeTime) {
            this.storeTime = Optional.fromNullable(storeTime);
            return this;
        }
        
        /** Associate an error to this event. If errorCode is null, the error information will
         * not be changed.
         * @param errorCode a short code for the error.
         * @param errorMessage a free text error message.
         * @param errorStackTrace the error stack trace.
         * @return this builder.
         */
        public Builder withNullableError(
                final String errorCode,
                final String errorMessage,
                final String errorStackTrace) {
            if (errorCode == null) {
                return this;
            }
            Utils.notNullOrEmpty(errorCode, "errorCode cannot be null or whitespace only");
            Utils.notNullOrEmpty(errorMessage, "errorMessage cannot be null or whitespace only");
            Utils.notNullOrEmpty(errorStackTrace,
                    "errorStackTrace cannot be null or whitespace only");
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.errorStackTrace = errorStackTrace;
            return this;
        }
        
        /** Build the {@link StoredStatusEvent}.
         * @return the event.
         */
        public StoredStatusEvent build() {
            return new StoredStatusEvent(event, id, state, updateTime, updater, workerCodes,
                    storedBy, storeTime, errorCode, errorMessage, errorStackTrace);
        }
    }
}
