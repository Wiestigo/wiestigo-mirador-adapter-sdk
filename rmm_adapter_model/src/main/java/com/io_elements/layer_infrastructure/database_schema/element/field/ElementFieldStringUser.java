package com.io_elements.layer_infrastructure.database_schema.element.field;


import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.*;

import java.util.Arrays;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ElementFieldStringUser extends ElementField<String>{

    private String userName;
    private String firstName;
    private String lastName;

    public ElementFieldStringUser(ElementId id, ElementName name, ElementLabel label, String userName, String firstName, String lastName) {
        super(id, name, label, null);
        this.userName = userName;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    /**
     * The display name: first name and last name, separated by a single
     * space. Either part may be missing (a technical account has no first
     * name); a missing part is left out
     * instead of producing a leading space or the text "null", because Mirador
     * stores the value as the user's display name exactly as returned.
     */
    @Override
    public String getValueAsString() {
        StringBuilder displayName = new StringBuilder();
        for (String part : new String[] {firstName, lastName}) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!displayName.isEmpty()) {
                displayName.append(" ");
            }
            displayName.append(part);
        }
        return displayName.toString();
    }

    @Override
    public String getValue() {
        return getValueAsString();
    }

    /**
     * Sets first and last name from a display name as {@link #getValue()}
     * delivers it ({@link #parseNameFromFirstNameLastName}); {@code null} or
     * blank text clears both. The user name is not touched. A display name
     * does not say where the first name ends: to keep a last name of several
     * words, set the parts with {@link #setFirstName} and {@link #setLastName}.
     */
    @Override
    public void setValue(String value) {
        if (value == null || value.isBlank()) {
            this.firstName = null;
            this.lastName = null;
            return;
        }
        String[] firstNameLastName = parseNameFromFirstNameLastName(value);

        this.firstName = firstNameLastName[0];
        this.lastName = firstNameLastName[1];
    }

    /**
     * Splits a display name of the form "first name last name" into
     * {first name, last name}: the last word is the last name, everything
     * before it the first name. A single word is the last name and the first
     * name stays {@code null}, so the split never fails on the form of a
     * name.
     *
     * @throws IllegalArgumentException if the input is {@code null} or blank
     */
    public static String[] parseNameFromFirstNameLastName(String firstNameAndLastName) {
        if (firstNameAndLastName == null || firstNameAndLastName.isBlank()) {
            throw new IllegalArgumentException("Input must not be null or blank");
        }

        String[] parts = firstNameAndLastName.trim().split("\\s+");

        if (parts.length == 1) {
            return new String[] {null, parts[0]};
        }

        String lastName = parts[parts.length - 1];
        String firstName = String.join(" ", Arrays.copyOf(parts, parts.length - 1));

        return new String[] {firstName, lastName};
    }
}
