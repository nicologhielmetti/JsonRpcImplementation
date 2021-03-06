package node;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import connectioninterfaces.IConnection;
import connectioninterfaces.IConnectionFactory;
import connectioninterfaces.TimeoutException;
import javafx.util.Pair;
import jsonrpclibrary.*;
import logger.Logger;
import searchstrategy.SearchStrategy;
import service.IServiceMethod;
import service.JsonRpcCustomError;

import service.Service;
import service.ServiceMetadata;

import java.util.*;

/**
 *  This class contains server side and client side function because when can have a single instance of Node as node
 *  that can do a client, a server or both.
 */

public class Node {

    private Map<String, Service> ownServices; /** Service that are provided by a node */
    private IConnectionFactory connectionFactory; /** It is used to create new connection */
    private int id; /** Every JSON-RPC request from a node have a different jsonrpclibrary.ID */
    private Timer timer; /** please see below


    // Start of Service handler functionality

    /**
     * Dictionary:
     * - Server ---> DummyServer.java
     * - Client ---> DummyClient.java
     */

    /**
     * Node is the constructor of the Node class.
     * The IConnectionFactory parameter is used to define which is used to define
     * the connection that this object have to use.
     * @param connectionFactory
     */

    public Node(IConnectionFactory connectionFactory) {
        this.id = 0;
        this.connectionFactory = connectionFactory;
        ownServices = new HashMap<>();
        // See description in checkPublishedService() method
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                checkPublishedService();
            }
        };
        timer = new Timer();
        timer.schedule(task,60000, 60000);
    }

    /**
     * provideService is public method that allow the application layer (servers) to provide
     * an own service to all clients connected in the system. With this method the server send
     * to the broker a request of adding service ("registerService") and the broker if the operation
     * is successful a response is return with the correct method name in order not to have duplicated
     * method identifier.
     * When the service is correctly published this method start a thread that wait requests from clients.
     * @param metadata, function
     */

    public boolean provideService(ServiceMetadata metadata, IServiceMethod function) {
        JsonRpcManager manager = new JsonRpcManager(this.connectionFactory.createConnection());
        Service service = new Service(metadata, function, manager);
        JsonRpcRequest registerServiceRequest = new JsonRpcRequest("registerService", metadata.toJson(), this.generateNewId());
        manager.send(registerServiceRequest);
        JsonRpcResponse registerServiceResponse;
        try {
            registerServiceResponse = (JsonRpcResponse) manager.listenResponse(1000);
        } catch (ParseException e) {
            e.printStackTrace();
            Logger.log( JsonRpcCustomError.localParseError().getCode() + " " + JsonRpcCustomError.localParseError().getMessage());
            return false;
        } catch (TimeoutException e) {
            Logger.log(JsonRpcCustomError.localParseError().getCode() + " " + JsonRpcCustomError.connectionTimeout().getMessage());
            return false;
        }

        // Read response from broker
        JsonObject result = registerServiceResponse.getResult().getAsJsonObject();
        boolean serviceRegistered = result.get("serviceRegistered").getAsBoolean();
        if (serviceRegistered) {
            String newMethodName = result.get("method").getAsString();
            metadata.setMethodName(newMethodName);
        } else {
            // Timeout
        }
        Logger.log("Server: Service registered!");
        // Start new service
        service.start();
        ownServices.put(metadata.getMethodName(), service);
        return true;
    }

    /**
     * deleteService is a public api that allow to the service owner to delete a service.
     * * This method send a particular JSON-RPC notification wih method = "deleteService", that is a particular service
     * inside the system broker, and a parameter that is the name of the method (Service) that must be deleted.
     * No response is  expected because broker must delete the service.
     * If there id no service provided by node with methodName = method an error is returned.
     * @param method
     */

    public void deleteService(String method) {
        if (this.ownServices.containsKey(method)) {
            IConnection connection = this.connectionFactory.createConnection();
            JsonRpcManager manager = new JsonRpcManager(connection);

            JsonObject jsonMethod = new JsonObject();
            jsonMethod.addProperty("method", method);

            JsonRpcRequest request = JsonRpcRequest.notification("deleteService", jsonMethod);
            manager.send(request);
            // Delete service
            this.ownServices.get(method).interrupt();
            this.ownServices.get(method).delete();
            this.ownServices.remove(method);
        } else {
            Logger.log("Server: There is no service named " + method);
        }
    }

    /**
     * This api is used to allow the node user to change at runtime the connection implementation used to connect to
     * broker that want to use. For example a node can change ip and port used.
     * @param connectionFactory
     */

    public void setConnectionFactory(IConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * This method is used only to check the services publication status on the system broker.
     * For example if the broker went down the list of the available services would be empty.
     * For this reason this function called every 60 seconds check if the services owned by node still published.
     * If are not (this means that broker goes down and now is up) the services would be provided again.
     */

    public void checkPublishedService() {
        ArrayList<ServiceMetadata> serviceRegistered = this.requestServiceList();
        Iterator<Map.Entry<String, Service>> i = ownServices.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String,Service> it = i.next();
            if (!serviceRegistered.contains(it)) {
                this.ownServices.remove(it);
                this.provideService(it.getValue().getServiceMetadata(), it.getValue().getFunction());
            }
        }
    }

    // End of Service handler functionality

    // Begin of Service requester functionality

    /**
     * requestService is a public api used to send single request to a service registered in the system broker.
     * This method send a JSON-RPC request and wait for JSON-RPC response :
     * - If the response is correctly received is returned to the requester.
     * - If the requested service is not available in the broker a JSON-RPC jsonrpclibrary.Error method not found is received as response.
     * - If the received JSON-RPC response can't be correctly parsed from the requester a custom JSON-RPC jsonrpclibrary.Error is returned
     *   and an error is printed to console.
     * - If timeout occurred, a custom error is returned.
     * @param method
     * @param parameters
     * @return
     */

    public JsonRpcResponse requestService(String method, JsonElement parameters) {
        JsonRpcManager manager = new JsonRpcManager(this.connectionFactory.createConnection());
        JsonRpcRequest request = new JsonRpcRequest(method, parameters, generateNewId());
        manager.send(request);
        JsonRpcResponse response = null;
        try {
            response = (JsonRpcResponse) manager.listenResponse(1000);
        } catch (ParseException e) {
            Logger.log("Client: Local parse exception: " + response.toString());
            response = JsonRpcResponse.error(JsonRpcCustomError.localParseError(), ID.Null());
        }  catch (TimeoutException e) {
            Logger.log("Timeout");
            response = JsonRpcResponse.error(JsonRpcCustomError.connectionTimeout(), ID.Null());
        }
        return response;
    }

    /**
     * requestService is a public api used to send batch request to one or more service registered in the system broker.
     * This method send a JSON-RPC batch request and wait for JSON-RPC batch response :
     * - If the response is correctly received is returned to the requester.
     * - If the requested service is not available in the broker a JSON-RPC jsonrpclibrary.Error method not found is received as response.
     * - If the received JSON-RPC response can't be correctly parsed from the requester a custom JSON-RPC jsonrpclibrary.Error is returned
     *   and an error is printed to console.
     * - If timeout occurred, a custom error is returned.
     * @param methodsAndParameters
     * @return
     */
    public JsonRpcBatchResponse requestService(ArrayList<Pair<String, JsonElement>> methodsAndParameters) {
        JsonRpcManager manager = new JsonRpcManager(this.connectionFactory.createConnection());
        JsonRpcBatchRequest requests = new JsonRpcBatchRequest();
        for (Pair<String, JsonElement> request: methodsAndParameters) {
            requests.add(new JsonRpcRequest(request.getKey(), request.getValue(), generateNewId()));
        }
        manager.send(requests);
        JsonRpcBatchResponse responses = new JsonRpcBatchResponse();
        try {
            responses = (JsonRpcBatchResponse) manager.listenResponse(1000);
        } catch (ParseException e) {
            Logger.log("Client: Local parse exception: " + responses.toString());
            responses.add(JsonRpcResponse.error(JsonRpcCustomError.localParseError(), ID.Null()));
        } catch (TimeoutException e) {
            Logger.log("Node: got timeout exception while waiting for batch response ("+e.getMessage()+")");
            responses.add(JsonRpcResponse.error(JsonRpcCustomError.connectionTimeout(), ID.Null()));
        }
        return responses;
    }

    /**
     * requestServiceList is a public api used to retrieve services registered in the system broker.
     * This method send a particular JSON-RPC request wih method = "getServicesList", that is a particular service inside
     * the system broker, and a parameter that is a searchStrategy that is an object that allow the user to define the
     * type of search that want perform. For more information see broker and SearchStrategy class.
     * @param searchStrategy
     * @return
     */

   public ArrayList<ServiceMetadata> requestServiceList(SearchStrategy searchStrategy) {
        ArrayList<ServiceMetadata> list = new ArrayList<>();
        list.clear();
        JsonRpcResponse response = this.requestService("getServicesList", searchStrategy.toJsonElement());
        if (!response.isError()) {
            JsonArray array = response.getResult().getAsJsonArray();
            Iterator<JsonElement> iterator = array.iterator();
            while (iterator.hasNext()) {
                list.add(ServiceMetadata.fromJson(iterator.next().getAsJsonObject()));
            }
        }
        return list;
    }
    /**
     * requestServiceList is a public api used to retrieve all services registered in the system broker.
     * @return a list containing all the available services
     */
    public ArrayList<ServiceMetadata> requestServiceList() {
        ArrayList<ServiceMetadata> list = new ArrayList<>();
        list.clear();
        JsonRpcResponse response = this.requestService("getServicesList", null);
        if (!response.isError()) {
            JsonArray array = response.getResult().getAsJsonArray();
            Iterator<JsonElement> iterator = array.iterator();
            while (iterator.hasNext()) {
                list.add(ServiceMetadata.fromJson(iterator.next().getAsJsonObject()));
            }
        }
        return list;
    }

    /** This method return all service that are correctly published by the node */
    public ArrayList<String> showRunningServices() {
        ArrayList<String> runningServicesName = new ArrayList<>();
        Iterator<Map.Entry<String, Service>> i = ownServices.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String,Service> it = i.next();
            Logger.log("Name: " + it.getKey() + " - " + it.getValue().getServiceMetadata().toJson());
            runningServicesName.add(it.getKey());
        }
        return runningServicesName;
    }

    // End of Service requester functionality

    /**
     * generateNewId is private a  method that increment the id every time a request is generated
     * @return
     * */
     private ID generateNewId() {
        return new ID(this.id++);
    }

    /**
     * This method delete all service in the system broker
     */
    public void close() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        timer.cancel();
        ArrayList<String> names = new ArrayList<>();
        for (Iterator<Map.Entry<String, Service>> i = ownServices.entrySet().iterator(); i.hasNext();){
            Map.Entry<String,Service> it = i.next();
            names.add(it.getKey());
        }
        for (Iterator<String> i = names.iterator(); i.hasNext();) {
            this.deleteService(i.next());
        }
    }
}
