package kbaserelationengine.events;

import java.io.IOException;
import java.util.List;

public interface ObjectStatusEventStorage {
	
	public void createStorage() throws IOException;
		
	public void deleteStorage() throws IOException;

	public void store(ObjectStatusEvent obj) throws IOException;
	
	public void markAsProcessed(ObjectStatusEvent row, boolean isIndexed) throws IOException;
	
	public void markAsNonprocessed(String storageCode, String storageObjectType) throws IOException;

	
		
	public int count(String storageCode, boolean processed) throws IOException;
	
	public List<ObjectStatusEvent> find(String storageCode, boolean processed, int maxSize) throws IOException;
	
	public ObjectStatusCursor cursor(String storageCode, boolean processed, int pageSize, String timeAlive) throws IOException;
	
	public boolean nextPage(ObjectStatusCursor cursor) throws IOException;
	
	public List<ObjectStatusEvent> find(String storageCode, int accessGroupId, List<String> accessGroupObjectIds) throws IOException;

	
	public List<AccessGroupStatus> findAccessGroups(String storageCode) throws IOException;
		
}
