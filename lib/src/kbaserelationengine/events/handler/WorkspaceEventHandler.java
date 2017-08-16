package kbaserelationengine.events.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import kbaserelationengine.events.ObjectStatusEvent;
import kbaserelationengine.events.ObjectStatusEventType;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple11;
import workspace.GetObjectInfo3Params;
import workspace.ListObjectsParams;
import workspace.ObjectIdentity;
import workspace.ObjectSpecification;
import workspace.WorkspaceClient;

/** A handler for events generated by the workspace service.
 * @author gaprice@lbl.gov
 *
 */
public class WorkspaceEventHandler implements EventHandler {
    
    //TODO JAVADOC
    //TODO TEST will need to mock the ws client

    /** The storage code for workspace events. */
    public static final String STORAGE_CODE = "WS";
    
    private static final int WS_BATCH_SIZE = 10_000;
    
    private final WorkspaceClient ws;
    
    /** Create a handler.
     * @param wsClient a workspace client to use when contacting the workspace service.
     */
    public WorkspaceEventHandler(final WorkspaceClient wsClient) {
        ws = wsClient;
    }

    @Override
    public Iterable<ObjectStatusEvent> expand(final ObjectStatusEvent event) {
        if (ObjectStatusEventType.NEW_ALL_VERSIONS.equals(event.getEventType())) {
            return handleNewAllVersions(event);
        } else if (ObjectStatusEventType.COPY_ACCESS_GROUP.equals(event.getEventType())) {
            return handleNewAccessGroup(event);
        } else {
            return Arrays.asList(event);
        }
    }

    //TODO NOW test this.
    private Iterable<ObjectStatusEvent> handleNewAccessGroup(final ObjectStatusEvent event) {
        return new Iterable<ObjectStatusEvent>() {

            @Override
            public Iterator<ObjectStatusEvent> iterator() {
                return new WorkspaceIterator(ws, event);
            }
            
        };
    }
    
    private static class WorkspaceIterator implements Iterator<ObjectStatusEvent> {
        
        private final WorkspaceClient ws;
        private final ObjectStatusEvent sourceEvent;
        private final int accessGroupId;
        private long processedObjs = 0;
        private LinkedList<ObjectStatusEvent> queue = new LinkedList<>();

        public WorkspaceIterator(final WorkspaceClient ws, final ObjectStatusEvent sourceEvent) {
            this.ws = ws;
            this.sourceEvent = sourceEvent;
            this.accessGroupId = sourceEvent.getAccessGroupId();
            fillQueue();
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty();
        }

        @Override
        public ObjectStatusEvent next() {
            if (queue.isEmpty()) {
                throw new NoSuchElementException();
            }
            final ObjectStatusEvent event = queue.removeFirst();
            if (queue.isEmpty()) {
                fillQueue();
            }
            return event;
        }

        private void fillQueue() {
            // as of 0.7.2 if only object id filters are used, workspace will sort by
            // ws asc, obj id asc, ver dec
            
            final ArrayList<ObjectStatusEvent> events;
            try {
                events = buildEvents(sourceEvent,
                        ws.listObjects(new ListObjectsParams()
                                .withIds(Arrays.asList((long) accessGroupId))
                                .withMinObjectID((long) processedObjs + 1)
                                .withShowHidden(1L)
                                .withShowAllVersions(1L)));
            } catch (JsonClientException | IOException e) {
                //TODO EXP some of these exceptions should be retries, some should shut down the event loop until the issue can be fixed (e.g. bad token, ws down), some should ignore the event (ws deleted)
                throw new IllegalStateException("Error contacting workspace: " + e.getMessage(),
                        e);
            }
            if (events.isEmpty()) {
                return;
            }
            // might want to do something smarter about the extra parse at some point
            final long first = Long.parseLong(events.get(0).getAccessGroupObjectId());
            final ObjectStatusEvent lastEv = events.get(events.size() - 1);
            long last = Long.parseLong(lastEv.getAccessGroupObjectId());
            // it cannot be true that there were <10K objects and the last object returned's
            // version was != 1
            if (first == last && events.size() == WS_BATCH_SIZE && lastEv.getVersion() != 1) {
                //holy poopsnacks, a > 10K version object
                queue.addAll(events);
                for (int i = lastEv.getVersion(); i > 1; i =- WS_BATCH_SIZE) {
                    fillQueueWithVersions(first, i - WS_BATCH_SIZE, i);
                }
            } else {
                // could be smarter about this later, rather than throwing away all the versions of
                // the last object
                // not too many objects will have enough versions to matter
                if (lastEv.getVersion() != 1) {
                    last--;
                }
                for (final ObjectStatusEvent e: events) {
                    if (Long.parseLong(e.getAccessGroupObjectId()) > last) { // *&@ parse
                        break;
                    }
                    queue.add(e);
                }
            }
            processedObjs = last;
        }

        // startVersion = inclusive, endVersion = exclusive
        private void fillQueueWithVersions(
                final long objectID,
                int startVersion,
                final int endVersion) {
            if (startVersion < 1) {
                startVersion = 1;
            }
            final List<ObjectSpecification> objs = new LinkedList<>();
            for (int ver = startVersion; ver < endVersion; ver++) {
                objs.add(new ObjectSpecification()
                        .withWsid((long) accessGroupId)
                        .withObjid(objectID)
                        .withVer((long) ver));
            }
            try {
                queue.addAll(buildEvents(sourceEvent,
                        ws.getObjectInfo3(new GetObjectInfo3Params()
                                .withObjects(objs)).getInfos()));
            } catch (JsonClientException | IOException e) {
                //TODO EXP some of these exceptions should be retries, some should shut down the event loop until the issue can be fixed (e.g. bad token, ws down), some should ignore the event (ws deleted)
                throw new IllegalStateException("Error contacting workspace: " + e.getMessage(),
                        e);
            }
        }
    }

    private Iterable<ObjectStatusEvent> handleNewAllVersions(final ObjectStatusEvent event) {
        final long objid;
        try {
            objid = Long.parseLong(event.getAccessGroupObjectId());
        } catch (NumberFormatException ne) {
            //TODO EXP this exception should prevent the event from being processed again
            throw new IllegalStateException("Illegal workspace object id: " +
                    event.getAccessGroupObjectId());
        }
        try {
            return buildEvents(event, 
                    ws.getObjectHistory(new ObjectIdentity()
                            .withWsid((long) event.getAccessGroupId())
                            .withObjid(objid)));
        } catch (JsonClientException | IOException e) {
            //TODO EXP some of these exceptions should be retries, some should shut down the event loop until the issue can be fixed (e.g. bad token, ws down), some should cause the event to be ignored (deleted object)
            throw new IllegalStateException("Error contacting workspace: " + e.getMessage(),
                    e);
        }
    }

    private static ArrayList<ObjectStatusEvent> buildEvents(
            final ObjectStatusEvent originalEvent,
            final List<Tuple11<Long, String, String, String, Long, String, Long, String,
                    String, Long, Map<String, String>>> objects) {
        final ArrayList<ObjectStatusEvent> events = new ArrayList<>();
        for (final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                Long, Map<String, String>> obj: objects) {
            events.add(buildEvent(originalEvent, obj));
        }
        return events;
    }
    
    private static ObjectStatusEvent buildEvent(
            final ObjectStatusEvent origEvent,
            final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                    Long, Map<String, String>> obj) {
        return new ObjectStatusEvent(
                null, // no mongo id
                STORAGE_CODE,
                origEvent.getAccessGroupId(),
                obj.getE1() + "",
                Math.toIntExact(obj.getE5()), // vers are always ints
                null, // not a share
                origEvent.getTimestamp(),
                obj.getE3().split("-")[0],
                ObjectStatusEventType.NEW_VERSION,
                origEvent.isGlobalAccessed());
    }

}
