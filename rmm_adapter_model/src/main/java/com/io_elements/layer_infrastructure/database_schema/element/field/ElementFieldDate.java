package com.io_elements.layer_infrastructure.database_schema.element.field;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Date field. Its string form follows the pattern
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ}: ISO-like, with milliseconds and a
 * numeric zone offset without a colon.
 */
@NoArgsConstructor
public class ElementFieldDate extends ElementField<ZonedDateTime> {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    public ElementFieldDate(ElementName name, ElementLabel label, ZonedDateTime value) {
        super(name, label, value);
    }

    public ElementFieldDate(ElementName name, ElementLabel label, String value) {
        this.name = name;
        this.label = label;
        setValue(value);
    }

    @Override
    public String getValueAsString() {
        return value == null ? null : value.format(FORMATTER);
    }

    @Override
    public void setValue(String value) {
        if (value != null) {
            this.value = ZonedDateTime.parse(value, FORMATTER);
        }
    }

    @Override
    public ZonedDateTime getValue() {
        return value;
    }

    @Override
    public void setValue(ZonedDateTime value) {
        this.value = value;
    }
}
