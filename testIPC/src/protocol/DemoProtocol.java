package protocol;

import org.apache.hadoop.ipc.VersionedProtocol;

public interface DemoProtocol extends VersionedProtocol {
    public static final long versionID = 01L;
    int mycall(String msg);
}
