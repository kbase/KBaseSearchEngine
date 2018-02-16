
package kbasesearchengine;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Generated;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;


/**
 * <p>Original spec-file type: SortingRule</p>
 * <pre>
 * Rule for sorting found results. 'key_name' is the keys that are sorted by. 
 * 'isTimestamp' and 'isWorkspaceId' are shortcuts.
 * Default order is ascending if 'ascending' field is not set).
 * </pre>
 * 
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Generated("com.googlecode.jsonschema2pojo")
@JsonPropertyOrder({
    "isTimestamp",
    "isWorkspaceId",
    "key_name",
    "ascending"
})
public class SortingRule {

    @JsonProperty("isTimestamp")
    private Long isTimestamp;
    @JsonProperty("isWorkspaceId")
    private Long isWorkspaceId;
    @JsonProperty("key_name")
    private String keyName;
    @JsonProperty("ascending")
    private Long ascending;
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();

    @JsonProperty("isTimestamp")
    public Long getIsTimestamp() {
        return isTimestamp;
    }

    @JsonProperty("isTimestamp")
    public void setIsTimestamp(Long isTimestamp) {
        this.isTimestamp = isTimestamp;
    }

    public SortingRule withIsTimestamp(Long isTimestamp) {
        this.isTimestamp = isTimestamp;
        return this;
    }

    @JsonProperty("isWorkspaceId")
    public Long getIsWorkspaceId() {
        return isWorkspaceId;
    }

    @JsonProperty("isWorkspaceId")
    public void setIsWorkspaceId(Long isWorkspaceId) {
        this.isWorkspaceId = isWorkspaceId;
    }

    public SortingRule withIsWorkspaceId(Long isWorkspaceId) {
        this.isWorkspaceId = isWorkspaceId;
        return this;
    }

    @JsonProperty("key_name")
    public String getKeyName() {
        return keyName;
    }

    @JsonProperty("key_name")
    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public SortingRule withKeyName(String keyName) {
        this.keyName = keyName;
        return this;
    }

    @JsonProperty("ascending")
    public Long getAscending() {
        return ascending;
    }

    @JsonProperty("ascending")
    public void setAscending(Long ascending) {
        this.ascending = ascending;
    }

    public SortingRule withAscending(Long ascending) {
        this.ascending = ascending;
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperties(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

    @Override
    public String toString() {
        return ((((((((((("SortingRule"+" [isTimestamp=")+ isTimestamp)+", isWorkspaceId=")+ isWorkspaceId)+", keyName=")+ keyName)+", ascending=")+ ascending)+", additionalProperties=")+ additionalProperties)+"]");
    }

}
