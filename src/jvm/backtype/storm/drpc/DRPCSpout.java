package backtype.storm.drpc;

import backtype.storm.Config;
import backtype.storm.ILocalDRPC;
import backtype.storm.generated.DRPCRequest;
import backtype.storm.generated.DistributedRPC;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.utils.DRPCClient;
import backtype.storm.utils.ServiceRegistry;
import backtype.storm.utils.Utils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.log4j.Logger;
import org.apache.thrift7.TException;
import org.json.simple.JSONValue;

//for local mode, should have a service lookup of some sort. DRPCSpout(LocalDRPC...)
public class DRPCSpout implements IRichSpout {
    public static Logger LOG = Logger.getLogger(DRPCSpout.class);
    
    SpoutOutputCollector _collector;
    List<DRPCClient> _clients = new ArrayList<DRPCClient>();
    String _function;
    String _local_drpc_id = null;
    
    private static class DRPCMessageId {
        String id;
        int index;
        
        public DRPCMessageId(String id, int index) {
            this.id = id;
            this.index = index;
        }
    }
    
    
    public DRPCSpout(String function) {
        _function = function;
    }

    public DRPCSpout(String function, ILocalDRPC drpc) {
        _function = function;
        _local_drpc_id = drpc.getServiceId();
    }
    
    
    @Override
    public boolean isDistributed() {
        return true;
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        _collector = collector;
        if(_local_drpc_id==null) {
            int numTasks = context.getComponentTasks(context.getThisComponentId()).size();
            int index = context.getThisTaskIndex();

            int port = ((Long) conf.get(Config.DRPC_PORT)).intValue();
            List<String> servers = (List<String>) conf.get(Config.DRPC_SERVERS);
            if(servers.isEmpty()) {
                throw new RuntimeException("No DRPC servers configured for topology");   
            }
            if(numTasks < servers.size()) {
                for(String s: servers) {
                    _clients.add(new DRPCClient(s, port));
                }
            } else {
                int i = index % servers.size();
                _clients.add(new DRPCClient(servers.get(i), port));
            }
        }
        
    }

    @Override
    public void close() {
        for(DRPCClient client: _clients) {
            client.close();
        }
    }

    @Override
    public void nextTuple() {
        boolean gotRequest = false;
        if(_local_drpc_id==null) {
            for(int i=0; i<_clients.size(); i++) {
                DRPCClient client = _clients.get(i);
                try {
                    DRPCRequest req = client.fetchRequest(_function);
                    if(req.get_request_id().length() > 0) {
                        Map returnInfo = new HashMap();
                        returnInfo.put("id", req.get_request_id());
                        returnInfo.put("host", client.getHost());
                        returnInfo.put("port", client.getPort());
                        gotRequest = true;
                        _collector.emit(new Values(req.get_func_args(), JSONValue.toJSONString(returnInfo)), new DRPCMessageId(req.get_request_id(), i));
                        break;
                    }
                } catch (TException e) {
                    LOG.error("Failed to fetch DRPC result from DRPC server", e);
                }
            }
        } else {
            DistributedRPC.Iface drpc = (DistributedRPC.Iface) ServiceRegistry.getService(_local_drpc_id);
            try {
                DRPCRequest req = drpc.fetchRequest(_function);
                if(req.get_request_id().length() > 0) {
                    Map returnInfo = new HashMap();
                    returnInfo.put("id", req.get_request_id());
                    returnInfo.put("host", _local_drpc_id);
                    returnInfo.put("port", 0);
                    gotRequest = true;
                    _collector.emit(new Values(req.get_func_args(), JSONValue.toJSONString(returnInfo)), new DRPCMessageId(req.get_request_id(), 0));
                }
            } catch (TException e) {
                throw new RuntimeException(e);
            }
        }
        if(!gotRequest) {
            Utils.sleep(1);
        }
    }

    @Override
    public void ack(Object msgId) {
    }

    @Override
    public void fail(Object msgId) {
        DRPCMessageId did = (DRPCMessageId) msgId;
        DistributedRPC.Iface client;
        
        if(_local_drpc_id == null) {
            client = _clients.get(did.index);
        } else {
            client = (ILocalDRPC) ServiceRegistry.getService(_local_drpc_id);
        }
        try {
            client.failRequest(did.id);
        } catch (TException e) {
            LOG.error("Failed to fail request", e);
        }        
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("args", "return-info"));
    }    
}
