package hudson.tasks.junit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import io.jenkins.plugins.junit.storage.FileJunitTestResultStorage;
import io.jenkins.plugins.junit.storage.JunitTestResultStorage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Downloads the recorded results of a build, at {@code <build>/testReportDownload/junitResult.xml} and
 * {@code <build>/testReportDownload/junitResult.xml.gz}.
 *
 * <p>The file is streamed from disk in fixed size chunks and never parsed, so the controller memory used by a
 * download does not depend on the size of the results. The plain file supports single byte ranges, so an
 * interrupted download can resume. The gzip variant is compressed while streaming.
 */
@Restricted(NoExternalUse.class)
public final class TestResultDownloadAction implements Action {

    static final String URL_NAME = "testReportDownload";
    static final String FILE_NAME = "junitResult.xml";
    static final String GZIP_NAME = FILE_NAME + ".gz";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final Run<?, ?> run;

    TestResultDownloadAction(Run<?, ?> run) {
        this.run = run;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    File getFile() {
        return new File(run.getRootDir(), FILE_NAME);
    }

    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        run.checkPermission(Run.ARTIFACTS);
        File file = getFile();
        String path = req.getRestOfPath();
        if (!file.isFile()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
        } else if (path.equals("/" + FILE_NAME)) {
            servePlain(req, rsp, file);
        } else if (path.equals("/" + GZIP_NAME)) {
            serveGzip(req, rsp, file);
        } else {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private static void servePlain(StaplerRequest2 req, StaplerResponse2 rsp, File file) throws IOException {
        long length = file.length();
        long lastModified = file.lastModified();
        String etag = "\"" + Long.toHexString(length) + "-" + Long.toHexString(lastModified) + "\"";
        rsp.setHeader("ETag", etag);
        rsp.setDateHeader("Last-Modified", lastModified);
        rsp.setHeader("Accept-Ranges", "bytes");
        // no-transform keeps proxies and the servlet container from compressing or rewriting the body
        rsp.setHeader("Cache-Control", "private, no-transform");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + FILE_NAME + "\"");
        if (etag.equals(req.getHeader("If-None-Match"))) {
            rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        long start = 0;
        long end = length - 1;
        String range = req.getHeader("Range");
        String ifRange = req.getHeader("If-Range");
        if (range != null && (ifRange == null || ifRange.equals(etag))) {
            Matcher m = RANGE.matcher(range.trim());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    long suffix = Long.parseLong(m.group(2));
                    start = Math.max(0, length - suffix);
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) {
                        end = Math.min(end, Long.parseLong(m.group(2)));
                    }
                }
                if (start >= length || start > end) {
                    // not sendError, which drops the headers set so far
                    rsp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    rsp.setHeader("Content-Range", "bytes */" + length);
                    rsp.setContentLength(0);
                    return;
                }
                rsp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                rsp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            }
            // a multi-range or malformed header is ignored and the whole file is sent, as RFC 9110 allows
        }
        long count = end - start + 1;
        rsp.setContentType("application/xml");
        rsp.setContentLengthLong(count);
        if ("HEAD".equals(req.getMethod())) {
            return;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            in.skipNBytes(start);
            copy(in, rsp.getOutputStream(), count);
        }
    }

    private static void serveGzip(StaplerRequest2 req, StaplerResponse2 rsp, File file) throws IOException {
        rsp.setContentType("application/gzip");
        rsp.setHeader("Cache-Control", "private, no-transform");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + GZIP_NAME + "\"");
        if ("HEAD".equals(req.getMethod())) {
            return;
        }
        try (InputStream in = Files.newInputStream(file.toPath());
                OutputStream out = new FastGzipOutputStream(rsp.getOutputStream())) {
            copy(in, out, Long.MAX_VALUE);
        }
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    /** Compresses at the fastest level, since a download should be limited by the network, not the CPU. */
    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream out) throws IOException {
            super(out, BUFFER_SIZE);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    @Extension
    public static final class Factory extends TransientActionFactory<Run> {
        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Run target) {
            // getAction(Class) would also ask the transient factories, this one included, so look at the
            // persisted actions only
            boolean recorded = target.getActions().stream().anyMatch(a -> a instanceof TestResultAction);
            if (!recorded || !(JunitTestResultStorage.find() instanceof FileJunitTestResultStorage)) {
                return Set.of();
            }
            return Set.of(new TestResultDownloadAction(target));
        }
    }
}
