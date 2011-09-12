package brooklyn.entity.nosql.gemfire

import java.net.URL
import java.util.Collection
import java.util.Map

import brooklyn.entity.Effector
import brooklyn.entity.Entity
import brooklyn.entity.ParameterType
import brooklyn.entity.basic.AbstractService
import brooklyn.entity.basic.BasicParameterType
import brooklyn.entity.basic.EffectorWithExplicitImplementation
import brooklyn.event.adapter.HttpSensorAdapter
import brooklyn.event.adapter.ValueProvider
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.event.basic.BasicConfigKey
import brooklyn.location.MachineLocation
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.SshBasedAppSetup

class GemfireServer extends AbstractService {
    public static final BasicConfigKey<String> INSTALL_DIR =
        [ String, "gemfire.server.installDir", "Gemfire installation directory" ]
    public static final BasicConfigKey<File> CONFIG_FILE = [ File, "gemfire.server.configFile", "Gemfire configuration file" ]
    public static final BasicConfigKey<File> JAR_FILE = [ File, "gemfire.server.jarFile", "Gemfire jar file" ]
    public static final BasicConfigKey<Integer> SUGGESTED_HUB_PORT =
        [ Integer, "gemfire.server.suggestedHubPort", "Gemfire gateway hub port", 11111 ]
    public static final BasicConfigKey<File> LICENSE = [ File, "gemfire.server.license", "Gemfire license file" ]

    public static final BasicAttributeSensor<Integer> HUB_PORT =
        [ Integer, "gemfire.server.hubPort", "Gemfire gateway hub port" ]
    public static final BasicAttributeSensor<String> CONTROL_URL =
        [ String, "gemfire.server.controlUrl", "URL for perfoming management actions" ]

    public static final Effector<Void> ADD_GATEWAYS =
        new EffectorWithExplicitImplementation<GemfireServer, Void>("addGateways", Void.TYPE,
            Arrays.<ParameterType<?>>asList(new BasicParameterType<Collection>("gateways", Collection.class,"Gatways to be added", Collections.emptyList())),
            "Add gateways to this server, to replicate to/from other clusters") {
        public Void invokeEffector(GemfireServer entity, Map m) {
            entity.addGateways((Collection<GatewayConnectionDetails>) m.get("gateways"));
            return null;
        }
    };

    private static final int CONTROL_PORT_VAL = 8084    
    transient HttpSensorAdapter httpAdapter

    public GemfireServer(Map properties=[:], Entity owner=null) {
        super(properties, owner)
    }

    @Override
    protected void initSensors() {
        int hubPort = getConfig(SUGGESTED_HUB_PORT)
        setAttribute(HUB_PORT, hubPort)
        setAttribute(CONTROL_URL, "http://${setup.machine.address.hostName}:"+CONTROL_PORT_VAL)
        
        httpAdapter = new HttpSensorAdapter(this)
        attributePoller.addSensor(SERVICE_UP, { computeNodeUp() } as ValueProvider)
    }
    
    public SshBasedAppSetup getSshBasedSetup(SshMachineLocation loc) {
        return GemfireSetup.newInstance(this, loc)
    }
    
    private boolean computeNodeUp() {
        String url = getAttribute(CONTROL_URL)
        ValueProvider<Integer> provider = httpAdapter.newStatusValueProvider(url)
        try {
            Integer statusCode = provider.compute()
            return (statusCode >= 200 && statusCode <= 299)
        } catch (IOException ioe) {
            return false
        }
    }

    public void addGateways(Collection<GatewayConnectionDetails> gateways) {
        int counter = 0
        gateways.each { GatewayConnectionDetails gateway ->
            String clusterId = gateway.clusterAbbreviatedName
            String endpointId = clusterId+"-"+(++counter)
            int port = gateway.port
            String hostname = gateway.host
            String controlUrl = getAttribute(CONTROL_URL)
            
            String urlstr = controlUrl+"/add?id="+clusterId+"&endpointId="+endpointId+"&port="+port+"&host="+hostname
            URL url = new URL(urlstr)
            HttpURLConnection connection = url.openConnection()
            connection.connect()
            int responseCode = connection.getResponseCode()
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("Failed to add gateway to server, response code $responseCode for using $url")
            }
        }
    }
}
