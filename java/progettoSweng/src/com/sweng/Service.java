package com.sweng;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.jsonrpc.*;

/**
 * Service class the contains all attributes and methods that allow the service execution and definition.
 */

public class Service extends Thread {

    private ServiceMetadata serviceMetadata; // All information about a service
    private JsonRpcManager manager; // It is used to receive request and send response (see JsonRpc Library)
    private IServiceMethod function; // Function that the service run implemented by the user (see IServiceMethod class)

    /**
     * Service class constructor.
     * @param serviceMetadata
     * @param function
     * @param manager
     */
    public Service(ServiceMetadata serviceMetadata, IServiceMethod function, JsonRpcManager manager) {
        this.serviceMetadata = serviceMetadata;
        this.function = function;
        this.manager = manager;
    }

    /**
     * run method is the implementation of the method that the Thread run.
     * In this method all type of request,response and error that a generic service can receive and send are handled.
     */
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            // Wait request
            JsonRpcMessage receivedRpcRequest = null;
            try {
                receivedRpcRequest = this.manager.listenRequest();
            } catch (ParseException e) {
                System.err.println("Server: Parse exeption");
                this.manager.send(JsonRpcDefaultError.parseError());
            }
            if (receivedRpcRequest.isBatch()) { //if is a batch request
                JsonRpcBatchRequest batch = (JsonRpcBatchRequest) receivedRpcRequest;
                List<JsonRpcRequest> requests = batch.get();
                List<JsonRpcResponse> responses = new ArrayList<>();
                for (Iterator<JsonRpcRequest> i = requests.iterator(); i.hasNext();) {
                    JsonRpcRequest request = i.next();
                    //if (!request.isEmpty()) {
                        JsonRpcResponse serviceResult = this.processRequest(request);
                        if (!request.isNotification()) // if is a notification no response is generated
                            responses.add(serviceResult);
                    //}
                }
                JsonRpcBatchResponse batchResponse = new JsonRpcBatchResponse();
                batchResponse.add(responses);
                this.manager.send(batchResponse);
            } else { // else if is a single JsonRpcRequest
                JsonRpcRequest request = (JsonRpcRequest) receivedRpcRequest;
                JsonRpcResponse serviceResult = this.processRequest(request);
                if (!request.isNotification()) {// if is a notification no response return is generated
                    // Send response
                    this.manager.send(serviceResult);
                }
            }
        }
    }

    /**
     * procesRequest handle the RuntimeException occurred when the IServiceMethod generate a RuntimeException.
     * @param request
     * @return
     */
    private JsonRpcResponse processRequest(JsonRpcRequest request) {
        try {
            return this.function.run(request);
        } catch(RuntimeException e) {
            System.err.println("Server: Runtime exeption in IServiceMethod implementation");
            return JsonRpcResponse.error(JsonRpcCustomError.internalServiceError(), request.getID());
        }
    }

    /**
     * getServiceMetadata return the service metadata of the service.
     * @return
     */
    public ServiceMetadata getServiceMetadata() {
        return this.serviceMetadata;
    }

    /** delete method destroy the service. */
    public void delete() {
        this.serviceMetadata = null;
        this.manager = null;
    }
}
