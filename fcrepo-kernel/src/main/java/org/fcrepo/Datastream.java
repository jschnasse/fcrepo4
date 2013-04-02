
package org.fcrepo;

import static com.google.common.base.Joiner.on;
import static com.yammer.metrics.MetricRegistry.name;
import static org.fcrepo.services.PathService.getDatastreamJcrNodePath;
import static org.fcrepo.services.RepositoryService.metrics;
import static org.fcrepo.services.ServiceHelpers.getNodePropertySize;
import static org.fcrepo.utils.FedoraTypesUtils.getBinary;
import static org.fcrepo.utils.FedoraTypesUtils.map;
import static org.fcrepo.utils.FedoraTypesUtils.value2string;
import static org.modeshape.jcr.api.JcrConstants.JCR_CONTENT;
import static org.modeshape.jcr.api.JcrConstants.JCR_DATA;
import static org.modeshape.jcr.api.JcrConstants.NT_FILE;
import static org.modeshape.jcr.api.JcrConstants.NT_RESOURCE;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.InputStream;
import java.net.URI;
import java.util.Calendar;
import java.util.Date;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;

import org.fcrepo.exception.InvalidChecksumException;
import org.fcrepo.services.LowLevelStorageService;
import org.fcrepo.utils.ContentDigest;
import org.fcrepo.utils.FedoraJcrTypes;
import org.modeshape.jcr.api.Binary;
import org.modeshape.jcr.api.JcrTools;
import org.slf4j.Logger;

import com.yammer.metrics.Histogram;

/**
 * Abstraction for a Fedora datastream backed by a JCR node.
 * 
 * @author ajs6f
 *
 */
public class Datastream extends JcrTools implements FedoraJcrTypes {

    private final static Logger logger = getLogger(Datastream.class);

    final static Histogram contentSizeHistogram = metrics.histogram(name(
            Datastream.class, "content-size"));

    Node node;

    LowLevelStorageService llStore;

    public Datastream(Node n) {
        super(false); // turn off debug logging
        this.node = n;
    }

    public Datastream(final Session session, String pid, String dsId)
            throws RepositoryException {
        this(session, getDatastreamJcrNodePath(pid, dsId));
    }

    public Datastream(final Session session, final String dsPath)
            throws RepositoryException {
        super(false);
        this.node = findOrCreateNode(session, dsPath, NT_FILE);
        if (this.node.isNew()) {
            this.node.addMixin(FEDORA_DATASTREAM);
            this.node.addMixin(FEDORA_OWNED);
            this.node.setProperty(FEDORA_OWNERID, session.getUserID());

            this.node.setProperty("jcr:lastModified", Calendar.getInstance());

            // TODO: I guess we should also have the PID + DSID..
            this.node.setProperty(DC_IDENTIFIER,
                    new String[] {
                            this.node.getIdentifier(),
                            this.node.getParent().getName() + "/" +
                                    this.node.getName()});
        }
    }

    /**
     * @return The backing JCR node.
     */
    public Node getNode() {
        return this.node;
    }

    /**
     * @return The InputStream of content associated with this datastream.
     * @throws RepositoryException
     */
    public InputStream getContent() throws RepositoryException {
        final Node contentNode = node.getNode(JCR_CONTENT);
        logger.trace("Retrieved datastream content node.");
        return contentNode.getProperty(JCR_DATA).getBinary().getStream();
    }

    /**
     * Sets the content of this Datastream.
     * 
     * @param content
     * @throws RepositoryException
     */
    public void setContent(InputStream content, String checksumType,
            String checksum) throws RepositoryException,
            InvalidChecksumException {
        final Node contentNode =
                findOrCreateChild(node, JCR_CONTENT, NT_RESOURCE);

        if (contentNode.canAddMixin(FEDORA_CHECKSUM)) {
            contentNode.addMixin(FEDORA_CHECKSUM);
        }

        logger.debug("Created content node at path: " + contentNode.getPath());

        /*
         * https://docs.jboss.org/author/display/MODE/Binary+values#Binaryvalues-
         * ExtendedBinaryinterface
         * promises: "All javax.jcr.Binary values returned by ModeShape will
         * implement this public interface, so feel free to cast the values to
         * gain access to the additional methods."
         */
        Binary binary = (Binary) getBinary(node, content);

        /*
         * This next line of code deserves explanation. If we chose for the
         * simpler line:
         * Property dataProperty = contentNode.setProperty(JCR_DATA,
         * requestBodyStream);
         * then the JCR would not block on the stream's completion, and we would
         * return to the requester before the mutation to the repo had actually
         * completed. So instead we use createBinary(requestBodyStream), because
         * its contract specifies:
         * "The passed InputStream is closed before this method returns either
         * normally or because of an exception."
         * which lets us block and not return until the job is done! The simpler
         * code may still be useful to us for an asynchronous method that we
         * develop later.
         */
        Property dataProperty = contentNode.setProperty(JCR_DATA, binary);

        String dsChecksum = binary.getHexHash();
        if (checksum != null && !checksum.equals("") &&
                !checksum.equals(binary.getHexHash())) {
            logger.debug("Failed checksum test");
            throw new InvalidChecksumException("Checksum Mismatch of " +
                    dsChecksum + " and " + checksum);
        }

        contentSizeHistogram.update(dataProperty.getLength());

        contentNode.setProperty(CONTENT_SIZE, dataProperty.getLength());
        contentNode.setProperty(DIGEST_VALUE, dsChecksum);
        contentNode.setProperty(DIGEST_ALGORITHM, "SHA-1");

        logger.debug("Created data property at path: " + dataProperty.getPath());

    }

    public void setContent(InputStream content) throws RepositoryException,
            InvalidChecksumException {
        setContent(content, null, null);
    }

    public void setContent(InputStream content, String mimeType,
            String checksumType, String checksum) throws RepositoryException,
            InvalidChecksumException {
        setContent(content, checksumType, checksum);
        node.setProperty(FEDORA_CONTENTTYPE, mimeType);
    }

    /**
     * @return The size in bytes of content associated with this datastream.
     * @throws RepositoryException
     */
    public long getContentSize() throws RepositoryException {
        return node.getNode(JCR_CONTENT).getProperty(CONTENT_SIZE).getLong();
    }

    public URI getContentDigest() throws RepositoryException {
        final Node contentNode = node.getNode(JCR_CONTENT);
        return ContentDigest
                .asURI(contentNode.getProperty(DIGEST_ALGORITHM).getString(),
                        contentNode.getProperty(DIGEST_VALUE).getString());
    }

    public String getContentDigestType() throws RepositoryException {
        return node.getNode(JCR_CONTENT).getProperty(DIGEST_ALGORITHM)
                .getString();
    }

    /**
     * @return The ID of this datastream, unique within an object. Normally just the name of the backing JCR node.
     * @throws RepositoryException
     */
    public String getDsId() throws RepositoryException {
        return node.getName();
    }

    /**
     * @return the FedoraObject to which this datastream belongs.
     * @throws RepositoryException
     */
    public FedoraObject getObject() throws RepositoryException {
        return new FedoraObject(node.getParent());
    }

    /**
     * @return The MimeType of content associated with this datastream.
     * @throws RepositoryException
     */
    public String getMimeType() throws RepositoryException {
        return node.hasProperty(FEDORA_CONTENTTYPE) ? node.getProperty(
                FEDORA_CONTENTTYPE).getString() : "application/octet-stream";
    }

    /**
     * @return A label associated with this datastream. Normally stored in a String-valued dc:title property.
     * @throws RepositoryException
     */
    public String getLabel() throws RepositoryException {
        if (node.hasProperty(DC_TITLE)) {

            Property labels = node.getProperty(DC_TITLE);
            String label;
            if (!labels.isMultiple()) {
                label = node.getProperty(DC_TITLE).getString();
            } else {
                label = on('/').join(map(labels.getValues(), value2string));
            }
            return label;
        } else {
            return "";
        }

    }

    public void setLabel(String label) throws ValueFormatException,
            VersionException, LockException, ConstraintViolationException,
            RepositoryException {
        node.setProperty(DC_TITLE, label);
    }

    public Date getCreatedDate() throws RepositoryException {
        return new Date(node.getProperty(JCR_CREATED).getDate()
                .getTimeInMillis());
    }

    public Date getLastModifiedDate() throws RepositoryException {
        return new Date(node.getProperty(JCR_LASTMODIFIED).getDate()
                .getTimeInMillis());
    }

    /**
     * Delete this datastream's underlying node
     */
    public void purge() throws RepositoryException {
        this.node.remove();
    }

    /**
     * Return the calculated size of the DS node
     * @return
     * @throws RepositoryException
     */
    public long getSize() throws RepositoryException {
        return getNodePropertySize(this.node) + getContentSize();

    }
}
