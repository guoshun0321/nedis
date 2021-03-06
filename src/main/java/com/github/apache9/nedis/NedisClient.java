package com.github.apache9.nedis;

import io.netty.util.concurrent.Future;

import com.github.apache9.nedis.protocol.ConnectionCommands;
import com.github.apache9.nedis.protocol.HashesCommands;
import com.github.apache9.nedis.protocol.HyperLogLogCommands;
import com.github.apache9.nedis.protocol.KeysCommands;
import com.github.apache9.nedis.protocol.ListsCommands;
import com.github.apache9.nedis.protocol.ScriptingCommands;
import com.github.apache9.nedis.protocol.ServerCommands;
import com.github.apache9.nedis.protocol.SetsCommands;
import com.github.apache9.nedis.protocol.SortedSetsCommands;
import com.github.apache9.nedis.protocol.StringsCommands;
import com.github.apache9.nedis.protocol.TransactionsCommands;

/**
 * @author Apache9
 */
public interface NedisClient extends ConnectionManagement, ConnectionCommands, KeysCommands,
        StringsCommands, ScriptingCommands, ListsCommands, SetsCommands, ServerCommands,
        HashesCommands, HyperLogLogCommands, SortedSetsCommands, TransactionsCommands {

    /**
     * General method to execute a redis command.
     */
    Future<Object> execCmd(byte[] cmd, byte[]... params);
}
