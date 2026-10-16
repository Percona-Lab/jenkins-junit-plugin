package hudson.tasks.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Run;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TouchBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TestResultDownloadActionTest {

    private JenkinsRule j;
    private FreeStyleBuild build;
    private byte[] file;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Run.ARTIFACTS)
                .everywhere()
                .to("alice")
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("bob"));
        FreeStyleProject p = j.createFreeStyleProject("p");
        p.getBuildersList().add(new TouchBuilder());
        p.getPublishersList().add(new JUnitResultArchiver("x.xml"));
        StringBuilder xml = new StringBuilder("<testsuite name='s'>");
        for (int i = 0; i < 2000; i++) {
            xml.append("<testcase classname='A' name='t").append(i).append("'/>");
        }
        j.jenkins
                .getWorkspaceFor(p)
                .child("x.xml")
                .write(xml.append("</testsuite>").toString(), "UTF-8");
        build = j.buildAndAssertSuccess(p);
        file = Files.readAllBytes(new File(build.getRootDir(), "junitResult.xml").toPath());
        base = j.getURL() + build.getUrl() + TestResultDownloadAction.URL_NAME + "/";
    }

    @Test
    void streamsTheRecordedFile() throws Exception {
        HttpResponse<byte[]> rsp = get("alice", TestResultDownloadAction.FILE_NAME, null);
        assertEquals(200, rsp.statusCode());
        assertArrayEquals(file, rsp.body());
        assertEquals(
                String.valueOf(file.length),
                rsp.headers().firstValue("Content-Length").orElse(null));
        assertEquals("bytes", rsp.headers().firstValue("Accept-Ranges").orElse(null));
    }

    @Test
    void streamsGzip() throws Exception {
        HttpResponse<byte[]> rsp = get("alice", TestResultDownloadAction.GZIP_NAME, null);
        assertEquals(200, rsp.statusCode());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(rsp.body()))) {
            assertArrayEquals(file, in.readAllBytes());
        }
    }

    @Test
    void servesByteRanges() throws Exception {
        HttpResponse<byte[]> rsp = get("alice", TestResultDownloadAction.FILE_NAME, "bytes=10-99");
        assertEquals(206, rsp.statusCode());
        assertEquals(
                "bytes 10-99/" + file.length,
                rsp.headers().firstValue("Content-Range").orElse(null));
        assertArrayEquals(Arrays.copyOfRange(file, 10, 100), rsp.body());

        HttpResponse<byte[]> suffix = get("alice", TestResultDownloadAction.FILE_NAME, "bytes=-50");
        assertEquals(206, suffix.statusCode());
        assertArrayEquals(Arrays.copyOfRange(file, file.length - 50, file.length), suffix.body());

        HttpResponse<byte[]> open = get("alice", TestResultDownloadAction.FILE_NAME, "bytes=100-");
        assertEquals(206, open.statusCode());
        assertArrayEquals(Arrays.copyOfRange(file, 100, file.length), open.body());

        HttpResponse<byte[]> beyond = get("alice", TestResultDownloadAction.FILE_NAME, "bytes=" + file.length + "-");
        assertEquals(416, beyond.statusCode());
        assertEquals(
                "bytes */" + file.length,
                beyond.headers().firstValue("Content-Range").orElse(null));
    }

    @Test
    void answersNotModifiedForTheSameEtag() throws Exception {
        String etag = get("alice", TestResultDownloadAction.FILE_NAME, null)
                .headers()
                .firstValue("ETag")
                .orElseThrow();
        HttpResponse<byte[]> rsp = http.send(
                request("alice", TestResultDownloadAction.FILE_NAME)
                        .header("If-None-Match", etag)
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, rsp.statusCode());
    }

    @Test
    void requiresTheArtifactsPermission() throws Exception {
        assertEquals(403, get("bob", TestResultDownloadAction.FILE_NAME, null).statusCode());
    }

    @Test
    void unknownFileIsNotFound() throws Exception {
        assertEquals(404, get("alice", "other.xml", null).statusCode());
    }

    @Test
    void notOfferedWithoutTestResults() throws Exception {
        FreeStyleBuild plain = j.buildAndAssertSuccess(j.createFreeStyleProject("plain"));
        assertNull(plain.getAction(TestResultDownloadAction.class));
    }

    private HttpResponse<byte[]> get(String user, String name, String range) throws Exception {
        HttpRequest.Builder req = request(user, name);
        if (range != null) {
            req.header("Range", range);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(String user, String name) {
        String auth = Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
        return HttpRequest.newBuilder(URI.create(base + name)).header("Authorization", "Basic " + auth);
    }
}
