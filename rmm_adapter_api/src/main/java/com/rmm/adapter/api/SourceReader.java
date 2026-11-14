package com.rmm.adapter.api;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;

import java.util.Optional;

/**
 * Read contract for synchronization from the tool to Mirador: delivers the
 * content of an external project page by page as transfer elements.
 *
 * <ul>
 *   <li><b>Paging:</b> the first call has {@code cursor == null}, every
 *       further call the {@link SourcePage#nextCursor()} of the previous page.
 *       The cursor is opaque to the sync core ({@link PageCursor}).</li>
 *   <li><b>Pauses:</b> after any page the host may pause the run; the next
 *       page with the same cursor may be requested minutes to hours later. If
 *       server-side state behind the cursor has expired by then (a database
 *       cursor, a scroll context), re-read from the last delivered position
 *       instead of failing. Duplicates do no harm; omissions do, because a
 *       full run treats what is missing as deleted. Keep no read open and do
 *       not work in the background after a call has returned.</li>
 *   <li><b>Deadline:</b> {@link SyncRunContext#deadline()} per call.</li>
 *   <li><b>Faithful and unfiltered:</b> the adapter delivers what the tool
 *       has; the mapping in the core decides which types and attributes
 *       Mirador takes. The adapter resolves tool IDs into display values (pick
 *       lists, users, releases) and renders the tool's text formats to
 *       HTML.</li>
 *   <li><b>Error isolation:</b> an element that cannot be converted goes into
 *       {@link SourcePage#failures()} and does not abort the page.</li>
 *   <li><b>Scope:</b> see {@link ReadScope}. A named element the tool no
 *       longer has is omitted without an error. Deletions are otherwise
 *       reconciled only in full runs, unless the tool has a deletion feed
 *       (such as an activity stream): a delta run then names the identifiers
 *       deleted since {@code changedSince} in
 *       {@link SourcePage#deletedIds()}.</li>
 *   <li><b>Phases:</b> {@code readArtifacts} is required. The core calls the
 *       methods of another phase only if the adapter declares that phase in
 *       {@link AdapterCapabilities#reads()}; the adapter must then override
 *       them (the TCK checks this), and the core reconciles the phase in a
 *       full run. The throwing defaults stand for "not declared".</li>
 *   <li><b>Order:</b> in every run the core reads artifacts first, then
 *       relationships, comments and attachments, and feature selections last;
 *       undeclared phases are left out. The TCK reads in the same order. What
 *       the artifact phase collects for later phases of the same run (such as
 *       the links of the issues read) belongs in
 *       {@link AdapterContext#runStore()}, not in a field of the
 *       instance.</li>
 *   <li><b>Attachments:</b> {@code readAttachments} delivers metadata only
 *       ({@link ElementAttachment}) and names in
 *       {@link SourcePage#completeParentIds()} the artifacts whose attachments
 *       are delivered completely. The core fetches bytes through
 *       {@code openAttachment} only for what it does not have: the same
 *       {@code contentHash}, or the same {@code byteCount} and
 *       {@code modifiedAt}, means no download. A delivered attachment passes
 *       the same checks as an upload (size, type, virus scan); a rejected one
 *       appears as a read error in the run.</li>
 *   <li><b>Embedded images:</b> an image in rich text is a reference inside
 *       the text, not an attachment; see {@link #openInlineImage}.</li>
 * </ul>
 */
public interface SourceReader {

    /** One page of artifacts of the external project. */
    SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope, PageCursor cursor)
            throws AdapterException;

    /** One page of relationships. Requires the declared phase {@link SyncPhase#RELATIONSHIPS}. */
    default SourcePage<ElementRelationship> readRelationships(SyncRunContext context, ReadScope scope,
                                                              PageCursor cursor) throws AdapterException {
        throw new AdapterException("This adapter does not declare the RELATIONSHIPS read phase —"
                + " readRelationships must not be called (capability reads)");
    }

    /** One page of comments. Requires the declared phase {@link SyncPhase#COMMENTS}. */
    default SourcePage<ElementComment> readComments(SyncRunContext context, ReadScope scope,
                                                    PageCursor cursor) throws AdapterException {
        throw new AdapterException("This adapter does not declare the COMMENTS read phase —"
                + " readComments must not be called (capability reads)");
    }

    /**
     * One page of attachments: metadata, no bytes. Requires the declared phase
     * {@link SyncPhase#ATTACHMENTS}.
     */
    default SourcePage<ElementAttachment> readAttachments(SyncRunContext context, ReadScope scope,
                                                          PageCursor cursor) throws AdapterException {
        throw new AdapterException("This adapter does not declare the ATTACHMENTS read phase —"
                + " readAttachments must not be called (capability reads)");
    }

    /**
     * The bytes of one attachment as a stream with its length. The core calls
     * this for every attachment it has to fetch and closes the content.
     * Requires the declared phase {@link SyncPhase#ATTACHMENTS}.
     *
     * @param externalId the identifier of the attachment in the tool
     *                   ({@link ElementAttachment#getIdValue()})
     */
    default AttachmentContent openAttachment(SyncRunContext context, String externalId) throws AdapterException {
        throw new AdapterException("This adapter does not declare the ATTACHMENTS read phase —"
                + " openAttachment must not be called (capability reads)");
    }

    /**
     * One page of feature selections: per tool element its selection as
     * qualified names. An empty selection is a statement and is delivered. The
     * {@link ReadScope} applies as for artifacts; in a delta run, a tool that
     * cannot tell which selections changed delivers all. Requires the declared
     * phase {@link SyncPhase#VARIABILITY}.
     */
    default SourcePage<ElementFeatures> readElementFeatures(SyncRunContext context, ReadScope scope,
                                                            PageCursor cursor) throws AdapterException {
        throw new AdapterException("This adapter does not declare the VARIABILITY read phase —"
                + " readElementFeatures must not be called (capability reads)");
    }

    /**
     * The bytes of an image embedded in rich text. The core finds every
     * {@code <img src="…">} in the delivered text itself and asks here; there
     * is no phase and no capability, because only the adapter can tell whether
     * a reference resolves. On success the core stores the image in the image
     * store of the target specification and rewrites the reference to the
     * Mirador address. The default, empty, leaves the text unchanged; the
     * image then loads only for readers who are also logged in to the tool.
     *
     * <p>The reference is the string from the text: an address, a file name,
     * whatever the tool writes. Return empty for a reference that does not
     * point to the adapter's own tool and never download it: otherwise a text
     * could make the host send the credentials of the integration account to
     * a foreign server.
     *
     * <p>The core closes the returned {@link AttachmentContent}. Throw an
     * {@link AdapterException} for an image the tool does not have (any more);
     * the core then keeps the reference and counts it instead of aborting the
     * run.
     *
     * @param parentExternalId the element whose text contains the reference;
     *                         some tools (such as Jira) write only a file name
     *                         and mean an attachment of exactly this element
     * @param reference        the reference as it appears in the delivered text
     */
    default Optional<AttachmentContent> openInlineImage(SyncRunContext context, String parentExternalId,
                                                        String reference) throws AdapterException {
        return Optional.empty();
    }
}
