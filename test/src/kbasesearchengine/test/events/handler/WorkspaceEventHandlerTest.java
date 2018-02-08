package kbasesearchengine.test.events.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentMatcher;

import com.google.common.collect.ImmutableMap;

import kbasesearchengine.common.GUID;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.UnprocessableEventIndexingException;
import kbasesearchengine.events.handler.CloneableWorkspaceClient;
import kbasesearchengine.events.handler.CloneableWorkspaceClientImpl;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.events.handler.WorkspaceEventHandler;
import kbasesearchengine.test.common.TestCommon;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;
import workspace.GetObjects2Params;
import workspace.GetObjects2Results;
import workspace.ObjectData;
import workspace.ObjectIdentity;
import workspace.ObjectSpecification;
import workspace.ProvenanceAction;
import workspace.SubAction;
import workspace.WorkspaceClient;

public class WorkspaceEventHandlerTest {
    
    // these are strictly unit tests.
    
    //TODO TEST add more tests. coverage is no where near 100%.
    
    @Test
    public void getStorageCode() {
        final CloneableWorkspaceClient cli = mock(CloneableWorkspaceClient.class);
        
        assertThat("incorrect storage code", new WorkspaceEventHandler(cli).getStorageCode(),
                is("WS"));
    }
    
    @Test
    public void constructFail() {
        try {
            new WorkspaceEventHandler(null);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(
                    got, new NullPointerException("clonableWorkspaceClient"));
        }
    }
    
    private class AdminGetObjectsAnswerMatcher implements ArgumentMatcher<UObject> {

        final String ref;
        
        public AdminGetObjectsAnswerMatcher(final String ref) {
            this.ref = ref;
        }
        
        @Override
        public boolean matches(final UObject command) {
            // the fact that kb-sdk doesn't compile in equals & hashcode is infuriating
            @SuppressWarnings("unchecked")
            final Map<String, Object> cmd = command.asClassInstance(Map.class);
            assertThat("incorrect command", cmd.get("command"), is("getObjects"));
            final GetObjects2Params p = UObject.transformObjectToObject(
                    cmd.get("params"), GetObjects2Params.class);
            assertThat("incorrect ignore errs", p.getIgnoreErrors(), is((Long) null));
            assertThat("incorrect nodata", p.getNoData(), is((Long) null));
            final List<ObjectSpecification> objslist = p.getObjects();
            assertThat("incorrect spec count", objslist.size(), is(1));
            final ObjectSpecification spec = objslist.get(0);
            assertThat("incorrect find ref path", spec.getFindReferencePath(), is((Long) null));
            assertThat("incorrect included", spec.getIncluded(), is((List<String>) null));
            assertThat("incorrect name", spec.getName(), is((String) null));
            assertThat("incorrect objid", spec.getObjid(), is((Long) null));
            assertThat("incorrect objpath", spec.getObjPath(), is((List<ObjectIdentity>) null));
            assertThat("incorrect refpath", spec.getObjRefPath(), is((List<String>) null));
            assertThat("incorrect ref", spec.getRef(), is(ref));
            assertThat("incorrect strict arrays", spec.getStrictArrays(), is((Long) null));
            assertThat("incorrect strict maps", spec.getStrictMaps(), is((Long) null));
            assertThat("incorrect to obj path", spec.getToObjPath(),
                    is((List<ObjectIdentity>) null));
            assertThat("incorrect to obj ref path", spec.getToObjRefPath(),
                    is((List<String>) null));
            assertThat("incorrect ver", spec.getVer(), is((Long) null));
            assertThat("incorrect ws", spec.getWorkspace(), is((String) null));
            assertThat("incorrect wsid", spec.getWsid(), is((Long) null));
            return true;
        }
        
    }
    
    @Test
    public void loadNoPathMinimal() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Collections.emptyList())
                                .withCreator("creator")
                                .withCopySourceInaccessible(0L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(new GUID("WS:1/2/3"), Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableMD5("checksum")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }
    
    @Test
    public void loadWithPathMaximalCopyRef() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3;4/5/6"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Arrays.asList(new ProvenanceAction()
                                        .withMethod("meth")
                                        .withService("serv")
                                        .withServiceVer("sver")
                                        .withSubactions(Arrays.asList(
                                                new SubAction()
                                                        .withCommit("commit")
                                                        .withName("serv.meth"),
                                                // this one should be ignored
                                                new SubAction()
                                                        .withCommit("commit2")
                                                        .withName("serv.meth2")))))
                                .withCreator("creator")
                                .withCopied("7/8/9")
                                .withCopySourceInaccessible(0L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(Arrays.asList(new GUID("WS:1/2/3"), new GUID("WS:4/5/6")),
                        Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableCopier("copier")
                .withNullableMD5("checksum")
                .withNullableCommitHash("commit")
                .withNullableMethod("meth")
                .withNullableModule("serv")
                .withNullableVersion("sver")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }
    
    @Test
    public void loadWith1ItemPathEmptySubActionsInaccessibleCopy() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Arrays.asList(new ProvenanceAction()
                                        .withMethod("meth")
                                        .withService("serv")
                                        .withServiceVer("sver")
                                        .withSubactions(Collections.emptyList())))
                                .withCreator("creator")
                                .withCopySourceInaccessible(1L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(Arrays.asList(new GUID("WS:1/2/3")),
                        Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableCopier("copier")
                .withNullableMD5("checksum")
                .withNullableMethod("meth")
                .withNullableModule("serv")
                .withNullableVersion("sver")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }
    
    @Test
    public void loadWithNullSubActions() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Arrays.asList(new ProvenanceAction()
                                        .withMethod("meth")
                                        .withService("serv")
                                        .withServiceVer("sver")
                                        .withSubactions(null)))
                                .withCreator("creator")
                                .withCopySourceInaccessible(0L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(Arrays.asList(new GUID("WS:1/2/3")),
                        Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableMD5("checksum")
                .withNullableMethod("meth")
                .withNullableModule("serv")
                .withNullableVersion("sver")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }
    
    @Test
    public void loadWithNoProvMethod() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Arrays.asList(new ProvenanceAction()
                                        .withService("serv")
                                        .withServiceVer("sver")
                                        .withSubactions(Arrays.asList(
                                                new SubAction()
                                                .withCommit("commit")
                                                .withName("serv.meth")))))
                                .withCreator("creator")
                                .withCopySourceInaccessible(0L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(Arrays.asList(new GUID("WS:1/2/3")),
                        Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableMD5("checksum")
                .withNullableModule("serv")
                .withNullableVersion("sver")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }
    
    @Test
    public void loadWithNoProvService() throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(argThat(new AdminGetObjectsAnswerMatcher("1/2/3"))))
                .thenReturn(new UObject(new GetObjects2Results().withData(Arrays.asList(
                        new ObjectData()
                                .withData(new UObject(ImmutableMap.of("genome", "data")))
                                .withProvenance(Arrays.asList(new ProvenanceAction()
                                        .withMethod("meth")
                                        .withServiceVer("sver")
                                        .withSubactions(Arrays.asList(
                                                new SubAction()
                                                .withCommit("commit")
                                                .withName("serv.meth")))))
                                .withCreator("creator")
                                .withCopySourceInaccessible(0L)
                                .withInfo(objTuple(2, "objname", "sometype", "date", 3, "copier",
                                        1, "wsname", "checksum", 44, Collections.emptyMap()))))));
        
        final SourceData sd = new WorkspaceEventHandler(clonecli)
                .load(Arrays.asList(new GUID("WS:1/2/3")),
                        Paths.get("somefile"));
        
        final SourceData expected = SourceData.getBuilder(
                new UObject(ImmutableMap.of("genome", "data")), "objname", "creator")
                .withNullableMD5("checksum")
                .withNullableMethod("meth")
                .withNullableVersion("sver")
                .build();
        
        compare(sd, expected);
        
        verify(wscli).setStreamingModeOn(true);
        verify(wscli)._setFileForNextRpcResponse(new File("somefile"));
    }

    private void compare(final SourceData sd, final SourceData expected) {
        assertThat("incorrect data", sd.getData().asClassInstance(Map.class),
                is(expected.getData().asClassInstance(Map.class)));
        assertThat("incorrect name", sd.getName(), is(expected.getName()));
        assertThat("incorrect creator", sd.getCreator(), is(expected.getCreator()));
        assertThat("incorrect copier", sd.getCopier(), is(expected.getCopier()));
        assertThat("incorrect commit", sd.getCommitHash(), is(expected.getCommitHash()));
        assertThat("incorrect method", sd.getMethod(), is(expected.getMethod()));
        assertThat("incorrect module", sd.getModule(), is(expected.getModule()));
        assertThat("incorrect version", sd.getVersion(), is(expected.getVersion()));
        assertThat("incorrect md5", sd.getMD5(), is(expected.getMD5()));
        assertThat("incorrect tags", sd.getSourceTags(), is(expected.getSourceTags()));
    }

    private Tuple11<Long, String, String, String, Long, String, Long, String, String, Long,
        Map<String, String>> objTuple(
            final long objid,
            final String name,
            final String type,
            final String date,
            final long version,
            final String user,
            final long wsid,
            final String workspace,
            final String chksum,
            final long size,
            final Map<String, String> meta) {
        return new Tuple11<Long, String, String, String, Long, String, Long, String, String, Long,
            Map<String, String>>()
                .withE1(objid)
                .withE2(name)
                .withE3(type)
                .withE4(date)
                .withE5(version)
                .withE6(user)
                .withE7(wsid)
                .withE8(workspace)
                .withE9(chksum)
                .withE10(size)
                .withE11(meta);
    }

    @Test
    public void loadFailNulls() {
        final Path f = Paths.get("foo");
        final GUID g = new GUID("WS:1/2/3");
        failLoadNulls((GUID) null, f, new NullPointerException("guid"));
        failLoadNulls(g, null, new NullPointerException("file"));
        
        failLoadNulls((List<GUID>) null, f, new NullPointerException("guids"));
        failLoadNulls(Arrays.asList(g), null, new NullPointerException("file"));
        failLoadNulls(Arrays.asList(g, null), null,
                new NullPointerException("null item in guids"));
    }
    
    private void failLoadNulls(final GUID guid, final Path file, final Exception expected) {
        try {
            new WorkspaceEventHandler(new CloneableWorkspaceClientImpl(
                    mock(WorkspaceClient.class)))
                    .load(guid, file);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }

    private void failLoadNulls(final List<GUID> guids, final Path file, final Exception expected) {
        try {
            new WorkspaceEventHandler(new CloneableWorkspaceClientImpl(
                    mock(WorkspaceClient.class)))
                    .load(guids, file);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }

    @Test
    public void loadFailBadStorageCode() {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        try {
            new WorkspaceEventHandler(clonecli)
                    .load(Arrays.asList(new GUID("FS:1/2/3/")), Paths.get("foo"));
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got,
                    new IllegalArgumentException("GUID FS:1/2/3 is not a workspace object"));
        }
    }
    
    @Test
    public void loadFailWSExceptions() throws Exception {
        failLoadWSException(new ConnectException("hot damn"),
                new FatalRetriableIndexingException("hot damn"));
        
        failLoadWSException(new IOException("pump yer brakes, kid"),
                new RetriableIndexingException("pump yer brakes, kid"));
        
        failLoadWSException(new UnauthorizedException("dvd commentary"),
                new FatalIndexingException("dvd commentary"));
        
        failLoadWSException(new JsonClientException(null), new UnprocessableEventIndexingException(
                "Null error message from workspace server"));
        
        failLoadWSException(new JsonClientException("Couldn't Login"),
                new FatalIndexingException("Workspace credentials are invalid: Couldn't Login"));
        
        failLoadWSException(new JsonClientException("Did not start Up Properly"),
                new FatalIndexingException(
                        "Fatal error returned from workspace: Did not start Up Properly"));
        
        failLoadWSException(new JsonClientException("That man's a national treasure"),
                new UnprocessableEventIndexingException(
                        "Unrecoverable error from workspace on fetching object: " +
                        "That man's a national treasure"));
    }
    
    private void failLoadWSException(final Exception toThrow, final Exception expected)
            throws Exception {
        final CloneableWorkspaceClient clonecli = mock(CloneableWorkspaceClient.class);
        final WorkspaceClient wscli = mock(WorkspaceClient.class);
        when(clonecli.getClientClone()).thenReturn(wscli);
        
        when(wscli.administer(any())).thenThrow(toThrow);
        
        
        try {
            new WorkspaceEventHandler(clonecli).load(new GUID("WS:1/2/3"), Paths.get("foo"));
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }
}