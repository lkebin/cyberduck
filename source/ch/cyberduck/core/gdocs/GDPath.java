package ch.cyberduck.core.gdocs;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.AbstractPath;
import ch.cyberduck.core.Acl;
import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.Credentials;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathFactory;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.StreamListener;
import ch.cyberduck.core.i18n.Locale;
import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.serializer.Deserializer;
import ch.cyberduck.core.serializer.Serializer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gdata.client.DocumentQuery;
import com.google.gdata.client.GDataProtocol;
import com.google.gdata.client.GoogleAuthTokenFactory;
import com.google.gdata.client.Service;
import com.google.gdata.client.http.HttpGDataRequest;
import com.google.gdata.client.spreadsheet.SpreadsheetService;
import com.google.gdata.data.DateTime;
import com.google.gdata.data.Link;
import com.google.gdata.data.MediaContent;
import com.google.gdata.data.OutOfLineContent;
import com.google.gdata.data.Person;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.acl.AclEntry;
import com.google.gdata.data.acl.AclFeed;
import com.google.gdata.data.acl.AclRole;
import com.google.gdata.data.acl.AclScope;
import com.google.gdata.data.docs.DocumentEntry;
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.DocumentListFeed;
import com.google.gdata.data.docs.FolderEntry;
import com.google.gdata.data.docs.PresentationEntry;
import com.google.gdata.data.docs.RevisionEntry;
import com.google.gdata.data.docs.RevisionFeed;
import com.google.gdata.data.docs.SpreadsheetEntry;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.util.AuthenticationException;
import com.google.gdata.util.ContentType;
import com.google.gdata.util.NotImplementedException;
import com.google.gdata.util.ServiceException;

public class GDPath extends Path {
    private static Logger log = Logger.getLogger(GDPath.class);

    private static class Factory extends PathFactory<GDSession> {
        @Override
        protected Path create(GDSession session, String path, int type) {
            return new GDPath(session, path, type);
        }

        @Override
        protected Path create(GDSession session, String parent, String name, int type) {
            return new GDPath(session, parent, name, type);
        }

        @Override
        protected Path create(GDSession session, String parent, Local file) {
            return new GDPath(session, parent, file);
        }

        @Override
        protected <T> Path create(GDSession session, T dict) {
            return new GDPath(session, dict);
        }
    }

    public static PathFactory factory() {
        return new Factory();
    }

    @Override
    protected void init(Deserializer dict) {
        String resourceIdObj = dict.stringForKey("ResourceId");
        if(resourceIdObj != null) {
            this.setResourceId(resourceIdObj);
        }
        String exportUriObj = dict.stringForKey("ExportUri");
        if(exportUriObj != null) {
            this.setExportUri(exportUriObj);
        }
        String documentTypeObj = dict.stringForKey("DocumentType");
        if(documentTypeObj != null) {
            this.setDocumentType(documentTypeObj);
        }
        super.init(dict);
    }

    @Override
    protected <S> S getAsDictionary(Serializer dict) {
        if(resourceId != null) {
            dict.setStringForKey(resourceId, "ResourceId");
        }
        if(exportUri != null) {
            dict.setStringForKey(exportUri, "ExportUri");
        }
        if(documentType != null) {
            dict.setStringForKey(documentType, "DocumentType");
        }
        return super.getAsDictionary(dict);
    }

    private final GDSession session;

    protected GDPath(GDSession s, String parent, String name, int type) {
        super(parent, name, type);
        this.session = s;
    }

    protected GDPath(GDSession s, String path, int type) {
        super(path, type);
        this.session = s;
    }

    protected GDPath(GDSession s, String parent, Local file) {
        super(parent, file);
        this.session = s;
    }

    protected <T> GDPath(GDSession s, T dict) {
        super(dict);
        this.session = s;
    }

    /**
     * Arbitrary file type not converted to Google Docs.
     */
    private static final String DOCUMENT_FILE_TYPE = "file";

    /**
     * Kind of document or folder. Type is currently one of:
     * document
     * drawing
     * folder
     * pdf
     * presentation
     * spreadsheet
     * form
     */
    private String documentType;

    public String getDocumentType() {
        if(null == documentType) {
            if(attributes().isDirectory()) {
                return FolderEntry.LABEL;
            }
            // Arbitrary file type not converted to Google Docs.
            return DOCUMENT_FILE_TYPE;
        }
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    /**
     * URL from where the document can be downloaded.
     */
    private String exportUri;

    /**
     * @return Download URL without export format.
     */
    public String getExportUri() {
        if(StringUtils.isBlank(exportUri)) {
            log.warn(String.format("Refetching Export URI for %s", this.toString()));
            AttributedList<AbstractPath> l = this.getParent().children();
            if(l.contains(this.getReference())) {
                exportUri = ((GDPath) l.get(this.getReference())).getExportUri();
            }
            else {
                log.error("Missing Export URI for " + this.toString());
            }
        }
        return exportUri;
    }

    public void setExportUri(String exportUri) {
        this.exportUri = exportUri;
    }

    /**
     * Resource ID. Contains both the document type and document ID.
     * For folders this is <code>folder:0BwoD_34YE1B4ZDFiZmMwNTAtMGFiMy00MmQ1LTg1NTQtNmFiYWFkNTg2MTQ3</code>
     */
    private String resourceId;

    public String getResourceId() {
        if(StringUtils.isBlank(resourceId)) {
            log.warn(String.format("Refetching Resource ID for %s", this.toString()));
            AttributedList<AbstractPath> l = this.getParent().children();
            if(l.contains(this.getReference())) {
                resourceId = ((GDPath) l.get(this.getReference())).getResourceId();
            }
            else {
                log.error("Missing Resource ID for " + this.toString());
            }
        }
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    private String documentUri;

    /**
     * @return The URL to the document editable in the web browser
     */
    public String getDocumentUri() {
        return documentUri;
    }

    public void setDocumentUri(String documentUri) {
        this.documentUri = documentUri;
    }

    private String getDocumentId() {
        // Removing document type from resourceId gives us the documentId
        return StringUtils.removeStart(this.getResourceId(), this.getDocumentType() + ":");
    }

    /**
     * @return Includes the protocol and hostname only
     */
    protected StringBuilder getPrivateFeed() {
        final StringBuilder feed = this.getHostUrl();
        feed.append("/feeds/default/private/full/");
        return feed;
    }

    private StringBuilder getHostUrl() {
        final StringBuilder feed = new StringBuilder(this.getSession().getHost().getProtocol().getScheme().toString()).append("://");
        feed.append(this.getSession().getHost().getHostname());
        return feed;
    }

    protected String getResourceFeed() throws MalformedURLException {
        return this.getPrivateFeed().append(this.getResourceId()).toString();
    }

    protected String getMediaFeed() throws MalformedURLException {
        return this.getHostUrl().append("/feeds/default/media/document%3A").append(this.getDocumentId()).toString();
    }

    protected String getUpdateSessionFeed() throws MalformedURLException {
        return new StringBuilder(this.getCreateSessionFeed()).append("/document%3A").append(this.getDocumentId()).toString();
    }

    protected String getCreateSessionFeed() throws MalformedURLException {
        final StringBuilder feed = this.getHostUrl().append("/feeds/upload/create-session/default/private/full/");
        if(this.isRoot()) {
            return feed.append("folder%3Aroot/contents").toString();
        }
        return feed.append("folder%3A").append(this.getDocumentId()).append("/contents").toString();
    }

    protected String getFolderFeed() throws MalformedURLException {
        final StringBuilder feed = this.getPrivateFeed();
        if(this.isRoot()) {
            return feed.append("folder%3Aroot/contents").toString();
        }
        return feed.append("folder%3A").append(this.getDocumentId()).append("/contents").toString();
    }

    protected String getAclFeed() throws MalformedURLException {
        final StringBuilder feed = new StringBuilder(this.getResourceFeed());
        return feed.append("/acl").toString();
    }

    public String getRevisionsFeed() throws MalformedURLException {
        final StringBuilder feed = new StringBuilder(this.getResourceFeed());
        return feed.append("/revisions").toString();
    }

    @Override
    public void readAcl() {
        try {
            this.getSession().check();
            this.getSession().message(MessageFormat.format(Locale.localizedString("Getting permission of {0}", "Status"),
                    this.getName()));
            Acl acl = new Acl();
            AclFeed feed = this.getSession().getClient().getFeed(new URL(this.getAclFeed()), AclFeed.class);
            for(AclEntry entry : feed.getEntries()) {
                AclScope scope = entry.getScope();
                AclScope.Type type = scope.getType();
                AclRole role = entry.getRole();
                if(type.equals(AclScope.Type.USER)) {
                    // Only editable if not owner of document. Changing owner is not supported.
                    boolean editable = !role.getValue().equals(AclRole.OWNER.getValue());
                    acl.addAll(new Acl.EmailUser(scope.getValue(), editable),
                            new Acl.Role(role.getValue(), editable));
                }
                else if(type.equals(AclScope.Type.DOMAIN)) {
                    // Google Apps Domain grant.
                    acl.addAll(new Acl.DomainUser(scope.getValue()), new Acl.Role(role.getValue()));
                }
                else if(type.equals(AclScope.Type.GROUP)) {
                    // Google Group email grant
                    acl.addAll(new Acl.GroupUser(scope.getValue(), true), new Acl.Role(role.getValue()));
                }
                else if(type.equals(AclScope.Type.DEFAULT)) {
                    // Value of scope is null. Default access for non authenticated
                    // users. Publicly shared with all users.
                    acl.addAll(new Acl.CanonicalUser(AclScope.Type.DEFAULT.name(), Locale.localizedString("Public"), false),
                            new Acl.Role(role.getValue()));
                }
                else {
                    log.warn(String.format("Unsupported scope:%s", type));
                }
            }
            this.attributes().setAcl(acl);
        }
        catch(IOException e) {
            this.error("Cannot read file attributes", e);
        }
        catch(ServiceException e) {
            this.error("Cannot read file attributes", e);
        }
    }

    @Override
    public void writeAcl(Acl acl, boolean recursive) {
        try {
            // Delete all previous ACLs before inserting updated set.
            AclFeed feed = this.getSession().getClient().getFeed(new URL(this.getAclFeed()), AclFeed.class);
            for(AclEntry entry : feed.getEntries()) {
                if(entry.getRole().toString().equals(AclRole.OWNER.toString())) {
                    // Do not remove owner of document
                    continue;
                }
                entry.delete();
            }
            for(Acl.User user : acl.keySet()) {
                if(!user.isValid()) {
                    continue;
                }
                if(!user.isEditable()) {
                    continue;
                }
                // The API supports sharing permissions on multiple levels. These values
                // correspond to the <gAcl:scope> type attribute
                AclScope scope = null;
                if(user instanceof Acl.EmailUser) {
                    // a user's email address. Creating an ACL entry that shares a document or folder with users will notify 
                    // relevant users via email that they have new access to the document or folder
                    scope = new AclScope(AclScope.Type.USER, user.getIdentifier());
                }
                else if(user instanceof Acl.GroupUser) {
                    // a Google Group email address
                    scope = new AclScope(AclScope.Type.GROUP, user.getIdentifier());
                }
                else if(user instanceof Acl.DomainUser) {
                    // a Google Apps domain.
                    scope = new AclScope(AclScope.Type.DOMAIN, user.getIdentifier());
                }
                else if(user instanceof Acl.CanonicalUser) {
                    if(user.getIdentifier().equals(AclScope.Type.DEFAULT.name())) {
                        // Publicly shared with all users
                        scope = new AclScope(AclScope.Type.DEFAULT, null);
                    }
                }
                if(null == scope) {
                    log.warn(String.format("Unsupported scope:%s", user));
                    continue;
                }
                for(Acl.Role role : acl.get(user)) {
                    if(!role.isValid()) {
                        continue;
                    }
                    AclEntry entry = new AclEntry();
                    entry.setScope(scope);
                    entry.setRole(new AclRole(role.getName()));
                    // Insert updated ACL entry for scope
                    this.getSession().getClient().insert(new URL(this.getAclFeed()), entry);
                }
            }
        }
        catch(IOException e) {
            this.error("Cannot change permissions", e);
        }
        catch(ServiceException e) {
            this.error("Cannot change permissions", e);
        }
        finally {
            this.attributes().clear(false, false, true, false);
        }
        if(attributes().isDirectory()) {
            if(recursive) {
                // All child objects of the folder reflect will reflect the new
                // sharing permission regardless.
            }
        }
    }

    @Override
    public GDSession getSession() {
        return session;
    }

    @Override
    public InputStream read(boolean check) throws IOException {
        if(check) {
            this.getSession().check();
        }
        MediaContent mc = new MediaContent();
        StringBuilder uri = new StringBuilder(this.getExportUri());
        final String type = this.getDocumentType();
        final GoogleAuthTokenFactory.UserToken token
                = (GoogleAuthTokenFactory.UserToken) this.getSession().getClient().getAuthTokenFactory().getAuthToken();
        try {
            if(type.equals(SpreadsheetEntry.LABEL)) {
                // Authenticate against the Spreadsheets API to obtain an auth token
                SpreadsheetService spreadsheet = null;
                GDSession session = this.getSession();
                spreadsheet = new SpreadsheetService(this.getSession().getUserAgent(),
                        session.new CustomTrustRequestFactory(),
                        session.new CustomTrustGoogleAuthTokenFactory(
                                spreadsheet, SpreadsheetService.SPREADSHEET_SERVICE, this.getSession().getUserAgent()));
                final Credentials credentials = this.getSession().getHost().getCredentials();
                try {
                    spreadsheet.setUserCredentials(credentials.getUsername(), credentials.getPassword());
                }
                catch(AuthenticationException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
                // Substitute the spreadsheets token for the docs token
                this.getSession().getClient().setUserToken(
                        ((GoogleAuthTokenFactory.UserToken) spreadsheet.getAuthTokenFactory().getAuthToken()).getValue());
            }
            if(StringUtils.isNotEmpty(getExportFormat(type))) {
                uri.append("&exportFormat=").append(getExportFormat(type));
            }
            mc.setUri(uri.toString());
            try {
                MediaSource ms = this.getSession().getClient().getMedia(mc);
                return ms.getInputStream();
            }
            catch(ServiceException e) {
                IOException failure = new IOException(e.getMessage());
                failure.initCause(e);
                throw failure;
            }
        }
        finally {
            // Restore docs token for our DocList client
            this.getSession().getClient().setUserToken(token.getValue());
        }
    }

    @Override
    protected void download(BandwidthThrottle throttle, StreamListener listener,
                            final boolean check, final boolean quarantine) {
        if(attributes().isFile()) {
            OutputStream out = null;
            InputStream in = null;
            try {
                if(check) {
                    this.getSession().check();
                }
                in = this.read(check);
                out = this.getLocal().getOutputStream(this.status().isResume());
                this.download(in, out, throttle, listener, quarantine);
            }
            catch(IOException e) {
                this.error("Download failed", e);
            }
            finally {
                IOUtils.closeQuietly(in);
                IOUtils.closeQuietly(out);
            }
        }
    }

    /**
     * Google Apps Premier domains can upload files of arbitrary type. Uploading an arbitrary file is
     * the same as uploading documents (with and without metadata), except there is no
     * restriction on the file's Content-Type. Unlike normal document uploads, arbitrary
     * file uploads preserve their original format/extension, meaning there is no loss in
     * fidelity when the file is stored in Google Docs.
     * <p/>
     * By default, uploaded document files will be converted to a native Google Docs format.
     * For example, an .xls upload will create a Google Spreadsheet. To keep the file as an Excel
     * spreadsheet (and therefore upload the file as an arbitrary file), specify the convert=false
     * parameter to preserve the original format. The convert parameter is true by default for
     * document files. The parameter will be ignored for types that cannot be
     * converted (e.g. .exe, .mp3, .mov, etc.).
     *
     * @param throttle The bandwidth limit
     * @param listener The stream listener to notify about bytes received and sent
     * @param check    Check for open connection and open if needed before transfer
     */
    @Override
    protected void upload(BandwidthThrottle throttle, StreamListener listener, boolean check) {
        try {
            if(attributes().isFile()) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = getLocal().getInputStream();
                    out = this.write(check);
                    this.upload(out, in, throttle, listener);
                }
                finally {
                    IOUtils.closeQuietly(in);
                    IOUtils.closeQuietly(out);
                }
                // The directory listing is no more current
                this.getParent().invalidate();
            }
        }
        catch(IOException e) {
            this.error("Upload failed", e);
        }
    }

    @Override
    public OutputStream write(boolean check) throws IOException {
        if(check) {
            this.getSession().check();
        }
        try {
            final String mime = this.getLocal().getMimeType();

            DocumentListEntry document;
            if(this.exists()) {
                // First, fetch entry using the resourceId
                URL url = new URL(this.getResourceFeed());
                document = this.getSession().getClient().getEntry(url, DocumentListEntry.class);
                this.setDocumentType(document.getType());
            }
            else {
                document = new DocumentListEntry();
                document.setTitle(new PlainTextConstruct(this.getName()));
            }
            StringBuilder feed;
            if(this.exists()) {
                // PUT /feeds/upload/create-session/default/private/full/document%3A12345 HTTP/1.1
                feed = new StringBuilder(this.getUpdateSessionFeed());
            }
            else {
                feed = new StringBuilder(((GDPath) this.getParent()).getCreateSessionFeed());
            }
            // Convertible to Google Docs file type. To create a resumable upload request for an arbitrary
            // file upload, include the convert=false parameter on this initial upload request
            feed.append("?convert=").append(this.isConversionSupported()
                    && Preferences.instance().getBoolean("google.docs.upload.convert"));
            if(this.isOcrSupported()) {
                // Image file type
                feed.append("&ocr=").append(Preferences.instance().getProperty("google.docs.upload.ocr"));
            }
            // To initiate a resumable upload session, send an HTTP POST request to the resumable-post link. The unique
            // upload URI will be used to upload the file chunks
            final Service.GDataRequest session;
            if(this.exists()) {
                session = this.getSession().getClient().createUpdateRequest(new URL(feed.toString()));
                session.setEtag(document.getEtag());
            }
            else {
                session = this.getSession().getClient().createInsertRequest(new URL(feed.toString()));
            }
            // Initialize a resumable media upload request.
            session.setHeader(GDataProtocol.Header.X_UPLOAD_CONTENT_TYPE, mime);
            session.setHeader(GDataProtocol.Header.X_UPLOAD_CONTENT_LENGTH, Long.toString(status().getLength()));
            final URL location;
            try {
                this.getSession().getClient().writeRequestData(session, document);
                session.execute();
                location = new URL(session.getResponseHeader("Location"));
            }
            finally {
                session.end();
            }
            final Service.GDataRequest request;
            request = this.getSession().getClient().createRequest(Service.GDataRequest.RequestType.UPDATE,
                    location, new ContentType(mime));
            request.setHeader("Content-Length", String.valueOf(status().getLength()));
            if(this.exists()) {
                if(this.status().isResume()) {
                    // Querying the status of an incomplete upload
                    Service.GDataRequest status = this.getSession().getClient().createRequest(Service.GDataRequest.RequestType.UPDATE,
                            location, new ContentType(mime));
                    // If your request is terminated prior to receiving an entry response from the server or
                    // if you receive an HTTP 503 response from the server, you can query the
                    // current status of the upload by issuing an empty PUT request on the unique upload URI
                    status.setHeader("Content-Length", String.valueOf(0));
                    status.setHeader("Content-Range", "bytes" + " " + "*/" + status().getLength());
                    try {
                        status.execute();
                        final String header = status.getResponseHeader("Range");
                        log.info(String.format("Content-Range reported by server:%s", header));
                        final long range = getNextByteIndexFromRangeHeader(header);
                        request.setHeader("Content-Range", (this.status().isResume() ? range : 0)
                                + "-" + (status().getLength() - 1)
                                + "/" + status().getLength()
                        );
                    }
                    catch(ServiceException e) {
                        log.warn("Resume upload failed:" + e.getMessage());
                        // Ignore several possible server errors. Reload instead.
                        this.status().setResume(false);
                    }
                }
            }
            if(request instanceof HttpGDataRequest) {
                // No internal buffering of request with a known content length
                // Use chunked upload with default chunk size.
                ((HttpGDataRequest) request).getConnection().setChunkedStreamingMode(0);
            }
            final OutputStream out = request.getRequestStream();
            return new OutputStream() {
                @Override
                public void close() throws IOException {
                    try {
                        try {
                            // Parse response for HTTP error message.
                            request.execute();
                        }
                        catch(ServiceException e) {
                            IOException failure = new IOException(e.getMessage());
                            failure.initCause(e);
                            throw failure;
                        }
                        finally {
                            request.end();
                        }
                    }
                    finally {
                        out.close();
                    }
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void write(byte[] b) throws IOException {
                    out.write(b);
                }

                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                }
            };
        }
        catch(ServiceException e) {
            IOException failure = new IOException(e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * Returns the next byte index identifying data that the server has not
     * yet received, obtained from an HTTP Range header (e.g., a header of
     * "Range: 0-55" would cause 56 to be returned).  <code>null</code> or
     * malformed headers cause 0 to be returned.
     *
     * @param rangeHeader in the server response
     * @return the byte index beginning where the server has yet to receive data
     */
    private long getNextByteIndexFromRangeHeader(String rangeHeader) {
        if(rangeHeader == null || rangeHeader.indexOf('-') == -1) {

            // No valid range header, start from the beginning of the file.
            return 0L;
        }

        Matcher rangeMatcher =
                Pattern.compile("[0-9]+-[0-9]+").matcher(rangeHeader);
        if(!rangeMatcher.find(1)) {

            // No valid range header, start from the beginning of the file.
            return 0L;
        }

        try {
            String[] rangeParts = rangeMatcher.group().split("-");

            // Ensure that the start of the range is 0.
            long firstByteIndex = Long.parseLong(rangeParts[0]);
            if(firstByteIndex != 0) {
                return 0L;
            }

            // Return the next byte index after the end of the range.
            long lastByteIndex = Long.parseLong(rangeParts[1]);
            return lastByteIndex + 1;
        }
        catch(NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Supported file formats: application/pdf, image/jpeg, image/png, image/gif
     *
     * @return True for image formats supported by OCR
     */
    protected boolean isOcrSupported() {
        return this.getMimeType().equals("application/pdf")
                || this.getMimeType().equals("image/png")
                || this.getMimeType().equals("image/jpeg")
                || this.getMimeType().endsWith("image/gif");
    }

    /**
     * @return True if the document, spreadsheet or presentation format is recognized by Google Docs.
     */
    protected boolean isConversionSupported() {
        // The convert parameter will be ignored for types that cannot be converted. Therefore we
        // can always return true.
        return true;
    }

    @Override
    public AttributedList<Path> list(final AttributedList<Path> children) {
        if(this.attributes().isDirectory()) {
            try {
                this.getSession().check();
                this.getSession().message(MessageFormat.format(Locale.localizedString("Listing directory {0}", "Status"),
                        this.getName()));

                children.addAll(this.list(new DocumentQuery(new URL(this.getFolderFeed()))));
            }
            catch(ServiceException e) {
                log.warn("Listing directory failed:" + e.getMessage());
                children.attributes().setReadable(false);
                if(this.cache().isEmpty()) {
                    this.error(e.getMessage(), e);
                }
            }
            catch(IOException e) {
                log.warn("Listing directory failed:" + e.getMessage());
                children.attributes().setReadable(false);
                if(this.cache().isEmpty()) {
                    this.error(e.getMessage(), e);
                }
            }
        }
        return children;
    }

    /**
     * @param query Document list query
     * @return List of documents found
     * @throws ServiceException Service error
     * @throws IOException      Transport error
     */
    private AttributedList<Path> list(DocumentQuery query) throws ServiceException, IOException {
        final AttributedList<Path> children = new AttributedList<Path>();
        DocumentListFeed pager = this.getSession().getClient().getFeed(query, DocumentListFeed.class);
        do {
            for(final DocumentListEntry entry : pager.getEntries()) {
                log.debug("Resource:" + entry.getResourceId());
                final String type = entry.getType();
                GDPath path = new GDPath(this.getSession(), this.getAbsolute(), entry.getTitle().getPlainText(),
                        FolderEntry.LABEL.equals(type) ? Path.DIRECTORY_TYPE : Path.FILE_TYPE);
                path.setParent(this);
                path.setDocumentType(type);
                if(!(entry.getContent() instanceof OutOfLineContent)) {
                    log.warn(String.format("Missing content in entry %s", entry.getTitle().getPlainText()));
                    continue;
                }
                // Download URL
                path.setExportUri(((OutOfLineContent) entry.getContent()).getUri());
                // Link to Google Docs Editor
                path.setDocumentUri(entry.getDocumentLink().getHref());
                path.setResourceId(entry.getResourceId());
                // Add unique document ID as checksum
                path.attributes().setChecksum(entry.getMd5Checksum());
                path.attributes().setETag(entry.getEtag());
                if(null != entry.getMediaSource()) {
                    path.attributes().setSize(entry.getMediaSource().getContentLength());
                }
                if(entry.getQuotaBytesUsed() > 0) {
                    path.attributes().setSize(entry.getQuotaBytesUsed());
                }
                final DateTime lastViewed = entry.getLastViewed();
                if(lastViewed != null) {
                    path.attributes().setAccessedDate(lastViewed.getValue());
                }
                for(Person person : entry.getAuthors()) {
                    path.attributes().setOwner(person.getEmail());
                }
                final DateTime updated = entry.getUpdated();
                if(updated != null) {
                    path.attributes().setModificationDate(updated.getValue());
                }
                if(children.contains(path.getReference())) {
                    // Google Docs allows files to be named the same. Not really a duplicate.
                    path.attributes().setDuplicate(true);
                    path.setReference(null);
                }
                // Add to listing
                children.add(path);
                if(path.attributes().isFile()) {
                    // Fetch revisions
                    if(Preferences.instance().getBoolean("google.docs.revisions.enable")) {
                        try {
                            final List<RevisionEntry> revisions = this.getSession().getClient().getFeed(
                                    new URL(path.getRevisionsFeed()), RevisionFeed.class).getEntries();
                            Collections.sort(revisions, new Comparator<RevisionEntry>() {
                                public int compare(RevisionEntry o1, RevisionEntry o2) {
                                    return o1.getUpdated().compareTo(o2.getUpdated());
                                }
                            });
                            int i = 0;
                            for(RevisionEntry revisionEntry : revisions) {
                                GDPath revision = new GDPath(this.getSession(), revisionEntry.getTitle().getPlainText(),
                                        FolderEntry.LABEL.equals(type) ? Path.DIRECTORY_TYPE : Path.FILE_TYPE);
                                revision.setParent(this);
                                revision.setDocumentType(type);
                                if(!(revisionEntry.getContent() instanceof OutOfLineContent)) {
                                    log.warn(String.format("Missing content in revision entry %s", revisionEntry.getTitle().getPlainText()));
                                    continue;
                                }
                                revision.setExportUri(((OutOfLineContent) revisionEntry.getContent()).getUri());
                                final long size = ((OutOfLineContent) revisionEntry.getContent()).getLength();
                                if(size > 0) {
                                    revision.attributes().setSize(size);
                                }
                                revision.attributes().setOwner(revisionEntry.getModifyingUser().getName());
                                revision.attributes().setModificationDate(revisionEntry.getUpdated().getValue());
                                // Versioning is enabled if non null.
                                revision.attributes().setVersionId(revisionEntry.getVersionId());
                                revision.attributes().setChecksum(revisionEntry.getMd5Checksum());
                                revision.attributes().setETag(revisionEntry.getEtag());
                                revision.attributes().setRevision(++i);
                                revision.attributes().setDuplicate(true);
                                // Add to listing
                                children.add(revision);
                            }
                        }
                        catch(NotImplementedException e) {
                            log.error("No revisions available:" + e.getMessage());
                        }
                    }
                }
            }
            Link next = pager.getNextLink();
            if(null == next) {
                // No link to next page.
                break;
            }
            // More pages available
            pager = this.getSession().getClient().getFeed(new URL(next.getHref()), DocumentListFeed.class);
        }
        while(pager.getEntries().size() > 0);
        return children;
    }

    @Override
    public String getMimeType() {
        if(attributes().isFile()) {
            final String exportFormat = getExportFormat(this.getDocumentType());
            if(StringUtils.isNotEmpty(exportFormat)) {
                return getMimeType(exportFormat);
            }
        }
        return super.getMimeType();
    }

    @Override
    public String getExtension() {
        if(attributes().isFile()) {
            final String exportFormat = getExportFormat(this.getDocumentType());
            if(StringUtils.isNotEmpty(exportFormat)) {
                return exportFormat;
            }
        }
        return super.getExtension();
    }

    @Override
    public String getName() {
        if(attributes().isFile()) {
            final String exportFormat = getExportFormat(this.getDocumentType());
            if(StringUtils.isNotEmpty(exportFormat)) {
                if(!super.getName().endsWith(exportFormat)) {
                    return super.getName() + "." + exportFormat;
                }
            }
        }
        return super.getName();
    }

    /**
     * @param type The document type
     * @return Export format property name or null if unknown document type that supports no conversion
     */
    protected static String getExportFormat(String type) {
        if(type.equals(DocumentEntry.LABEL)) {
            return Preferences.instance().getProperty("google.docs.export.document");
        }
        if(type.equals(PresentationEntry.LABEL)) {
            return Preferences.instance().getProperty("google.docs.export.presentation");
        }
        if(type.equals(SpreadsheetEntry.LABEL)) {
            return Preferences.instance().getProperty("google.docs.export.spreadsheet");
        }
        if(type.equals(DOCUMENT_FILE_TYPE)) {
            // For files not converted to Google Docs.
            // DOCUMENT_FILE_TYPE
            log.debug("No output format conversion for document type:" + type);
            return null;
        }
        log.warn("Unknown document type:" + type);
        return null;
    }

    @Override
    public void mkdir() {
        if(this.attributes().isDirectory()) {
            try {
                this.getSession().check();
                this.getSession().message(MessageFormat.format(Locale.localizedString("Making directory {0}", "Status"),
                        this.getName()));

                DocumentListEntry folder = new FolderEntry();
                folder.setTitle(new PlainTextConstruct(this.getName()));
                try {
                    DocumentListEntry entry = this.getSession().getClient().insert(new URL(((GDPath) this.getParent()).getFolderFeed()), folder);
                    this.setExportUri(((OutOfLineContent) entry.getContent()).getUri());
                    this.setDocumentUri(entry.getDocumentLink().getHref());
                    this.setResourceId(entry.getResourceId());
                }
                catch(ServiceException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
                this.cache().put(this.getReference(), AttributedList.<Path>emptyList());
                // The directory listing is no more current
                this.cache().get(this.getParent().getReference()).add(this);
            }
            catch(IOException e) {
                this.error("Cannot create folder {0}", e);
            }
        }
    }

    @Override
    public void delete() {
        try {
            if(this.attributes().isDuplicate()) {
                log.warn("Cannot delete revision " + this.attributes().getRevision());
                return;
            }
            this.getSession().check();
            this.getSession().message(MessageFormat.format(Locale.localizedString("Deleting {0}", "Status"),
                    this.getName()));
            try {
                StringBuilder feed = new StringBuilder(this.getResourceFeed());
                if(!Preferences.instance().getBoolean("google.docs.delete.trash")) {
                    feed.append("?delete=true");
                }
                this.getSession().getClient().delete(
                        new URL(feed.toString()), this.attributes().getETag());
            }
            catch(ServiceException e) {
                IOException failure = new IOException(e.getMessage());
                failure.initCause(e);
                throw failure;
            }
            catch(MalformedURLException e) {
                IOException failure = new IOException(e.getMessage());
                failure.initCause(e);
                throw failure;
            }
            // The directory listing is no more current
            this.getParent().invalidate();
        }
        catch(IOException e) {
            this.error("Cannot delete {0}", e);
        }
    }

    @Override
    public void rename(AbstractPath renamed) {
        try {
            this.getSession().check();
            this.getSession().message(MessageFormat.format(Locale.localizedString("Renaming {0} to {1}", "Status"),
                    this.getName(), renamed));

            DocumentListEntry moved = new DocumentListEntry();
            moved.setId("https://docs.google.com/feeds/id/" + this.getResourceId());
            if(this.getParent().equals(renamed.getParent())) {
                // Rename file
                moved.setTitle(new PlainTextConstruct(renamed.getName()));
                try {
                    // Move into new folder
                    this.getSession().getClient().update(new URL(this.getResourceFeed()), moved, this.attributes().getETag());
                }
                catch(ServiceException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
                catch(MalformedURLException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
            }
            else {
                try {
                    // Move into new folder
                    final DocumentListEntry update
                            = this.getSession().getClient().insert(new URL(((GDPath) renamed.getParent()).getFolderFeed()), moved);
                    // Move out of previous folder
                    this.getSession().getClient().delete(new URL((((GDPath) this.getParent()).getFolderFeed()) +
                            "/" + this.getResourceId()), update.getEtag());
                }
                catch(ServiceException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
                catch(MalformedURLException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
            }
            // The directory listing of the target is no more current
            renamed.getParent().invalidate();
            // The directory listing of the source is no more current
            this.getParent().invalidate();
        }
        catch(IOException e) {
            this.error("Cannot rename {0}", e);
        }
    }

    @Override
    public void touch() {
        if(this.attributes().isFile()) {
            try {
                this.getSession().check();
                this.getSession().message(MessageFormat.format(Locale.localizedString("Uploading {0}", "Status"),
                        this.getName()));

                DocumentListEntry file = new DocumentEntry();
                file.setTitle(new PlainTextConstruct(this.getName()));
                try {
                    this.getSession().getClient().insert(new URL(((GDPath) this.getParent()).getFolderFeed()), file);
                }
                catch(ServiceException e) {
                    IOException failure = new IOException(e.getMessage());
                    failure.initCause(e);
                    throw failure;
                }
                // The directory listing is no more current
                this.getParent().invalidate();
            }
            catch(IOException e) {
                this.error("Cannot create file {0}", e);
            }
        }
    }

    @Override
    public String toHttpURL() {
        return this.getDocumentUri();
    }
}
