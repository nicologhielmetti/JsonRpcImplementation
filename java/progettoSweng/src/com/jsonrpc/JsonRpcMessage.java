package com.jsonrpc;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public abstract class JsonRpcMessage {
    protected JSONObject json;
    protected static JSONParser parser;

    public void setJsonRpcVersion(String jsonRpc){
        json.put("jsonrpc",jsonRpc);
    }

    public String getJsonRpcVersion(){
        return (String) json.get("jsonrpc");
    }

    public void setId(int id){
        json.put("id",id);
    }

    public int getId(){
        return (int) json.get("id");
    }

    public static boolean isResponse(JSONObject jsonObject){
        return (jsonObject.containsKey("jsonrpc") &&
                (jsonObject.containsKey("result") ^ jsonObject.containsKey("error")) ||
                jsonObject.containsKey("id"));
    }

    public static boolean isError(JSONObject jsonObject){
        return (isResponse(jsonObject) && jsonObject.containsKey("error"));
    }

    public static boolean isRequest(JSONObject jsonObject){
        return (jsonObject.containsKey("jsonrpc") &&
                jsonObject.containsKey("method")  &&
                jsonObject.containsKey("id"));
    }

    public static boolean isNotification(JSONObject jsonObject){
        return (jsonObject.containsKey("jsonrpc") &&
                jsonObject.containsKey("method")  &&
                !jsonObject.containsKey("id"));
    }

    private static boolean isJsonRpc(JSONObject jsonObject){
        return (isResponse(jsonObject) || isError(jsonObject)) ^ (isRequest(jsonObject) || isNotification(jsonObject));
    }

    public static JSONObject toJsonRpcObject(String jsonString){
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            if(isJsonRpc(jsonObject))
                return jsonObject;
        } catch (ParseException e) {
            return null;
        }
    }

    public JSONObject getJsonRpc() {
        return json;
    }

    public String getJsonRpcString(){
        return json.toJSONString();
    }
}
