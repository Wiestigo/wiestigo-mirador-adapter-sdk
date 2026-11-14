package unit_tests;

import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.ImageReferenceForm;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image reference forms an adapter declares (since contract 1.1): a
 * closed grammar with fixed bans, applied both when a form is declared and
 * when a value is checked.
 */
class TestImageReferenceForm {

    private static final ImageReferenceForm ATTACHMENT = ImageReferenceForm.scheme("attachment");
    private static final ImageReferenceForm WEB_ATTACHMENT =
            ImageReferenceForm.pathTemplate("/{ctx}/attachment/{number}/{file}");

    /** Every fixed ban makes the declaration impossible. */
    @Test
    void everyBanRefusesTheDeclaration() {
        for (String banned : new String[]{"http", "https", "data", "javascript", "vbscript", "file", "blob",
                "HTTPS", "JavaScript"}) {
            assertThrows(IllegalArgumentException.class, () -> ImageReferenceForm.scheme(banned), banned);
        }
        String[] templates = {
                "/a//{file}", "/a/../{file}", "/a/%2e/{file}", "/api/{number}/{file}", "/API/x/{file}",
                "/{ctx}/actuator/{file}", "/internal/{file}", "/{ctx}/{file}", "/{ctx}/{ctx}/a/{file}",
                "/a/{file}/b", "a/{file}", "/a/{file}/", "/a\\b/{file}", "/{count}/a", "/a b/{file}", "/.a/{file}"};
        for (String template : templates) {
            assertThrows(IllegalArgumentException.class, () -> ImageReferenceForm.pathTemplate(template), template);
        }
        for (String scheme : new String[]{"attachment:", "1abc", "a/b", " ", "a b"}) {
            assertThrows(IllegalArgumentException.class, () -> ImageReferenceForm.scheme(scheme), scheme);
        }
    }

    /**
     * The last segment of a path template is {@code {file}}, the file name of an image with an image
     * extension. Otherwise something like {@code <img src="/logout">} would remain, and the browser would call
     * a route on the origin of Mirador.
     */
    @Test
    void aPathTemplateEndsWithTheFileName() {
        for (String template : new String[]{"/{ctx}/logout", "/attachment/{number}", "/a/b", "/{ctx}/a/{number}"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ImageReferenceForm.pathTemplate(template), template);
            assertTrue(refused.getMessage().contains("{file}"), refused.getMessage());
        }
        assertEquals(List.of(), ImageReferenceForm.problemsOf(ImageReferenceForm.Kind.PATH_TEMPLATE,
                "/{ctx}/attachment/{number}/{file}"));
    }

    /**
     * Besides ASCII whitespace, the file name filter excludes every control, format and separator character
     * ({@code \p{Cc}}, {@code \p{Cf}}, {@code \p{Z}}); otherwise they would end up in the wiki markup of the
     * tool on the way back ({@code !...!}).
     */
    @Test
    void aFileNameCarriesNoControlFormatOrSeparatorCharacter() {
        for (String character : new String[]{"\u0000", "\u0085", "\u2028", "\u2029", "\u202E", "\uFEFF",
                "\u00A0", "\u200B", "\u3000", "\t", "\u001F", "\u007F"}) {
            String name = "a" + character + "b.png";
            assertFalse(ImageReferenceForm.FILE_NAME.matcher(name).matches(), "U+" + Integer.toHexString(
                    character.charAt(0)));
            assertTrue(ATTACHMENT.admit("attachment:" + name).isEmpty(), name);
            assertTrue(WEB_ATTACHMENT.admit("/attachment/1/" + name).isEmpty(), name);
        }
        assertTrue(ImageReferenceForm.FILE_NAME.matcher("Plan_v2-final(ä).png").matches(),
                "letters outside ASCII stay allowed");
    }

    /** A declared form admits only its own references, in their stored form. */
    @Test
    void aFormAdmitsOnlyItsOwnReferences() {
        assertEquals(Optional.of("attachment:image.png"), ATTACHMENT.admit("attachment:image.png"));
        assertEquals(Optional.of("attachment:plan.png"), ATTACHMENT.admit("ATTACHMENT:plan.png|thumbnail"));
        assertEquals(Optional.of("/attachment/17/image.png"), WEB_ATTACHMENT.admit("/attachment/17/image.png"));
        assertEquals(Optional.of("/jama/attachment/16363/image-16363.png"),
                WEB_ATTACHMENT.admit("/jama/attachment/16363/image-16363.png"));
        String[] refused = {
                "attachment:dir/x.png", "attachment:a b.png", "attachment:report.pdf", "attachment:",
                "attachment:%2e%2e.png", "/attachment/17/%2e%2e%2fapi.png", "/attachment/x/a.png",
                "/attachment/17", "//evil.example/attachment/1/a.png", "/api/attachment/1/a.png",
                "/x/../api/attachment/1/a.png", "/a/b/attachment/1/a.png", "/actuator/attachment/1/a.png",
                "/attachment/1/a.png?x=1", "/attachment/1/.png", "https://jama.example/attachment/1/a.png"};
        for (String reference : refused) {
            assertTrue(ATTACHMENT.admit(reference).isEmpty() && WEB_ATTACHMENT.admit(reference).isEmpty(),
                    reference);
        }
    }

    /** The example of a form is a reference of that form (the TCK relies on it). */
    @Test
    void theExampleIsAReferenceOfTheForm() {
        assertEquals(Optional.of(ATTACHMENT.example()), ATTACHMENT.admit(ATTACHMENT.example()));
        assertEquals(Optional.of(WEB_ATTACHMENT.example()), WEB_ATTACHMENT.admit(WEB_ATTACHMENT.example()));
    }

    /** The constructor of contract 1.0 stays and declares no image reference form. */
    @Test
    void withoutDeclarationNoImageReferenceForm() {
        AdapterCapabilities before = new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null);

        assertEquals(Set.of(), before.imageReferenceForms());
        assertEquals(Set.of(ATTACHMENT), before.withImageReferenceForms(Set.of(ATTACHMENT)).imageReferenceForms());
    }
}
