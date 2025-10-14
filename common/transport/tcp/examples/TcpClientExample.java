/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Thingsboard TCP Transport Java Client Example
 * 
 * This example demonstrates how to connect to Thingsboard using TCP transport
 * and send telemetry and attributes data.
 * 
 * Requirements:
 *     - Java 8+
 *     - Thingsboard server with TCP transport enabled
 */

import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;

public class TcpClientExample {
    
    private static final String HOST = "localhost";
    private static final int PORT = 8883;
    private static final String DEVICE_TOKEN = "YOUR_DEVICE_TOKEN";
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected = false;
    private Gson gson = new Gson();
    
    /**
     * Connect to Thingsboard server and authenticate
     */
    public boolean connect(boolean useProtobuf) {
        try {
            // Create socket connection
            socket = new Socket(HOST, PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            System.out.println("Connected to " + HOST + ":" + PORT);
            
            // Send authentication message
            String authMsg = useProtobuf ? 
                "AUTH:" + DEVICE_TOKEN + ":PROTO" : 
                "AUTH:" + DEVICE_TOKEN;
            out.println(authMsg);
            
            // Wait for authentication response
            String response = in.readLine();
            System.out.println("Auth response: " + response);
            
            if ("AUTH_OK".equals(response)) {
                connected = true;
                System.out.println("Authentication successful!");
                return true;
            } else {
                System.out.println("Authentication failed!");
                disconnect();
                return false;
            }
            
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send telemetry data
     */
    public boolean sendTelemetry(Map<String, Object> data) {
        if (!connected) {
            System.err.println("Not connected!");
            return false;
        }
        
        try {
            String json = gson.toJson(data);
            String message = "TELEMETRY:" + json;
            out.println(message);
            
            String response = in.readLine();
            System.out.println("Telemetry response: " + response);
            
            return "TELEMETRY_OK".equals(response);
        } catch (IOException e) {
            System.err.println("Error sending telemetry: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send attributes
     */
    public boolean sendAttributes(Map<String, Object> data) {
        if (!connected) {
            System.err.println("Not connected!");
            return false;
        }
        
        try {
            String json = gson.toJson(data);
            String message = "ATTRIBUTES:" + json;
            out.println(message);
            
            String response = in.readLine();
            System.out.println("Attributes response: " + response);
            
            return "ATTRIBUTES_OK".equals(response);
        } catch (IOException e) {
            System.err.println("Error sending attributes: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Disconnect from server
     */
    public void disconnect() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
            connected = false;
            System.out.println("Disconnected");
        } catch (IOException e) {
            System.err.println("Error during disconnect: " + e.getMessage());
        }
    }
    
    /**
     * Main method
     */
    public static void main(String[] args) {
        TcpClientExample client = new TcpClientExample();
        
        // Connect to server
        if (!client.connect(false)) {
            System.err.println("Failed to connect to server");
            System.exit(1);
        }
        
        try {
            // Send telemetry data
            System.out.println("\nSending telemetry data...");
            Map<String, Object> telemetry = new HashMap<>();
            telemetry.put("temperature", 25.5);
            telemetry.put("humidity", 60);
            telemetry.put("pressure", 1013.25);
            client.sendTelemetry(telemetry);
            
            // Send attributes
            System.out.println("\nSending attributes...");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("model", "T1000");
            attributes.put("firmwareVersion", "1.0.0");
            attributes.put("serialNumber", "SN12345");
            client.sendAttributes(attributes);
            
            // Send telemetry periodically
            System.out.println("\nSending telemetry every 5 seconds (Press Ctrl+C to stop)...");
            
            while (true) {
                telemetry = new HashMap<>();
                telemetry.put("temperature", 20 + Math.random() * 10);
                telemetry.put("humidity", 50 + Math.random() * 20);
                
                System.out.println("\nSending: " + telemetry);
                client.sendTelemetry(telemetry);
                
                Thread.sleep(5000);
            }
            
        } catch (InterruptedException e) {
            System.out.println("\n\nStopping...");
        } finally {
            client.disconnect();
        }
    }
}

