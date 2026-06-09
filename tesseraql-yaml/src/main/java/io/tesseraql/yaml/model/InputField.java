package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Declaration of a single route input parameter (design ch. 6.3).
 *
 * <p>Inputs are whitelisted: only declared fields are bound from the request, and constraints
 * here drive validation and default application during request binding.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InputField(
        String type,
        boolean required,
        @JsonProperty("default") Object defaultValue,
        Integer min,
        Integer max,
        Integer maxLength,
        @JsonProperty("enum") List<String> enumValues,
        InputItems items) {

    /** Element type for array inputs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputItems(String type, @JsonProperty("enum") List<String> enumValues) {
    }
}
