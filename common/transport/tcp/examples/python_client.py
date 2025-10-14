#!/usr/bin/env python3
#
# Copyright © 2016-2025 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Thingsboard TCP Transport Python Client Example

This example demonstrates how to connect to Thingsboard using TCP transport
and send telemetry and attributes data.

Requirements:
    - Python 3.6+
    - Thingsboard server with TCP transport enabled

Usage:
    python python_client.py
"""

import socket
import json
import time
import sys

class ThingsboardTcpClient:
    """Thingsboard TCP Transport Client"""
    
    def __init__(self, host='localhost', port=8883, device_token='YOUR_DEVICE_TOKEN'):
        """
        Initialize the client
        
        Args:
            host: Thingsboard server host
            port: TCP transport port
            device_token: Device access token
        """
        self.host = host
        self.port = port
        self.device_token = device_token
        self.socket = None
        self.connected = False
    
    def connect(self, use_protobuf=False):
        """
        Connect to the server and authenticate
        
        Args:
            use_protobuf: If True, use Protobuf format, otherwise use JSON
            
        Returns:
            bool: True if connection and authentication successful
        """
        try:
            # Create socket connection
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.connect((self.host, self.port))
            print(f"Connected to {self.host}:{self.port}")
            
            # Send authentication message
            if use_protobuf:
                auth_msg = f"AUTH:{self.device_token}:PROTO\n"
            else:
                auth_msg = f"AUTH:{self.device_token}\n"
            
            self.socket.send(auth_msg.encode())
            
            # Wait for authentication response
            response = self.socket.recv(1024).decode().strip()
            print(f"Auth response: {response}")
            
            if response == "AUTH_OK":
                self.connected = True
                print("Authentication successful!")
                return True
            else:
                print("Authentication failed!")
                self.disconnect()
                return False
                
        except Exception as e:
            print(f"Connection error: {e}")
            return False
    
    def send_telemetry(self, data):
        """
        Send telemetry data
        
        Args:
            data: Dictionary containing telemetry data
            
        Returns:
            bool: True if successful
        """
        if not self.connected:
            print("Not connected!")
            return False
        
        try:
            telemetry_msg = f"TELEMETRY:{json.dumps(data)}\n"
            self.socket.send(telemetry_msg.encode())
            
            response = self.socket.recv(1024).decode().strip()
            print(f"Telemetry response: {response}")
            
            return response == "TELEMETRY_OK"
        except Exception as e:
            print(f"Error sending telemetry: {e}")
            return False
    
    def send_attributes(self, data):
        """
        Send attributes data
        
        Args:
            data: Dictionary containing attributes data
            
        Returns:
            bool: True if successful
        """
        if not self.connected:
            print("Not connected!")
            return False
        
        try:
            attr_msg = f"ATTRIBUTES:{json.dumps(data)}\n"
            self.socket.send(attr_msg.encode())
            
            response = self.socket.recv(1024).decode().strip()
            print(f"Attributes response: {response}")
            
            return response == "ATTRIBUTES_OK"
        except Exception as e:
            print(f"Error sending attributes: {e}")
            return False
    
    def disconnect(self):
        """Disconnect from the server"""
        if self.socket:
            self.socket.close()
            self.connected = False
            print("Disconnected")


def main():
    """Main function"""
    
    # Configuration
    HOST = 'localhost'
    PORT = 8883
    DEVICE_TOKEN = 'YOUR_DEVICE_TOKEN'  # Replace with your device token
    
    # Create client
    client = ThingsboardTcpClient(HOST, PORT, DEVICE_TOKEN)
    
    # Connect to server
    if not client.connect(use_protobuf=False):
        print("Failed to connect to server")
        sys.exit(1)
    
    try:
        # Send telemetry data
        print("\nSending telemetry data...")
        telemetry = {
            "temperature": 25.5,
            "humidity": 60,
            "pressure": 1013.25
        }
        client.send_telemetry(telemetry)
        
        # Send attributes
        print("\nSending attributes...")
        attributes = {
            "model": "T1000",
            "firmwareVersion": "1.0.0",
            "serialNumber": "SN12345"
        }
        client.send_attributes(attributes)
        
        # Send telemetry periodically
        print("\nSending telemetry every 5 seconds (Press Ctrl+C to stop)...")
        import random
        
        while True:
            telemetry = {
                "temperature": round(20 + random.random() * 10, 2),
                "humidity": round(50 + random.random() * 20, 2)
            }
            
            print(f"\nSending: {telemetry}")
            client.send_telemetry(telemetry)
            
            time.sleep(5)
            
    except KeyboardInterrupt:
        print("\n\nStopping...")
    finally:
        client.disconnect()


if __name__ == "__main__":
    main()

