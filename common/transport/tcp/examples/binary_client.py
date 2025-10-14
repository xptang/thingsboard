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
Thingsboard TCP Binary Protocol Python Client

This client implements the binary frame protocol with:
- Header: 0xAA55
- Length: 2 bytes (big-endian)
- Payload: variable
- CRC16: 2 bytes (big-endian)
- Footer: 0x55AA
"""

import socket
import struct
import json
import time

class BinaryTcpClient:
    """Binary Protocol TCP Client"""
    
    # Protocol constants
    HEADER = bytes([0xAA, 0x55])
    FOOTER = bytes([0x55, 0xAA])
    
    def __init__(self, host='localhost', port=8883):
        self.host = host
        self.port = port
        self.sock = None
    
    def calculate_crc16(self, data):
        """
        Calculate CRC16-CCITT
        Polynomial: 0x1021
        Initial: 0xFFFF
        """
        crc = 0xFFFF
        
        for byte in data:
            crc ^= byte << 8
            
            for _ in range(8):
                if crc & 0x8000:
                    crc = (crc << 1) ^ 0x1021
                else:
                    crc = crc << 1
                crc &= 0xFFFF
        
        return crc
    
    def build_frame(self, payload):
        """Build binary frame"""
        if isinstance(payload, str):
            payload = payload.encode('utf-8')
        
        frame = bytearray()
        
        # Header
        frame.extend(self.HEADER)
        
        # Length (2 bytes, big-endian)
        length = len(payload)
        frame.extend(struct.pack('>H', length))
        
        # Payload
        frame.extend(payload)
        
        # CRC16 (2 bytes, big-endian)
        crc = self.calculate_crc16(payload)
        frame.extend(struct.pack('>H', crc))
        
        # Footer
        frame.extend(self.FOOTER)
        
        return bytes(frame)
    
    def parse_frame(self, timeout=5):
        """Parse binary frame from socket"""
        self.sock.settimeout(timeout)
        
        try:
            # Read header
            header = self.sock.recv(2)
            if header != self.HEADER:
                raise ValueError(f"Invalid header: {header.hex()}")
            
            # Read length
            length_bytes = self.sock.recv(2)
            length = struct.unpack('>H', length_bytes)[0]
            
            # Read payload
            payload = b''
            while len(payload) < length:
                chunk = self.sock.recv(length - len(payload))
                if not chunk:
                    raise ValueError("Connection closed")
                payload += chunk
            
            # Read CRC
            crc_bytes = self.sock.recv(2)
            received_crc = struct.unpack('>H', crc_bytes)[0]
            
            # Verify CRC
            calculated_crc = self.calculate_crc16(payload)
            if calculated_crc != received_crc:
                raise ValueError(f"CRC mismatch: expected {calculated_crc:04X}, got {received_crc:04X}")
            
            # Read footer
            footer = self.sock.recv(2)
            if footer != self.FOOTER:
                raise ValueError(f"Invalid footer: {footer.hex()}")
            
            return payload.decode('utf-8')
        
        except socket.timeout:
            raise TimeoutError("Timeout waiting for response")
    
    def connect(self):
        """Connect to server"""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))
        print(f"✓ Connected to {self.host}:{self.port}")
    
    def authenticate(self, token):
        """Authenticate with device token"""
        print(f"\n→ Authenticating with token: {token}")
        
        # Send auth frame
        frame = self.build_frame(f"AUTH:{token}")
        print(f"  Sending frame ({len(frame)} bytes): {frame[:20].hex()}...")
        self.sock.sendall(frame)
        
        # Receive response
        response = self.parse_frame()
        print(f"← Response: {response}")
        
        if response.strip() == "AUTH_OK":
            print("✓ Authentication successful!")
            return True
        else:
            print("✗ Authentication failed!")
            return False
    
    def send_telemetry(self, data):
        """Send telemetry data"""
        message = f"TELEMETRY:{json.dumps(data)}"
        print(f"\n→ Sending telemetry: {data}")
        
        frame = self.build_frame(message)
        print(f"  Frame ({len(frame)} bytes): {frame[:20].hex()}...")
        self.sock.sendall(frame)
        
        response = self.parse_frame()
        print(f"← Response: {response}")
        
        return response.strip() == "TELEMETRY_OK"
    
    def send_attributes(self, data):
        """Send attributes"""
        message = f"ATTRIBUTES:{json.dumps(data)}"
        print(f"\n→ Sending attributes: {data}")
        
        frame = self.build_frame(message)
        self.sock.sendall(frame)
        
        response = self.parse_frame()
        print(f"← Response: {response}")
        
        return response.strip() == "ATTRIBUTES_OK"
    
    def disconnect(self):
        """Disconnect from server"""
        if self.sock:
            self.sock.close()
            print("\n✓ Disconnected")

def main():
    """Main function"""
    HOST = 'localhost'
    PORT = 8883
    TOKEN = 'T2_TEST_TOKEN'  # Replace with your device token
    
    client = BinaryTcpClient(HOST, PORT)
    
    try:
        # Connect
        client.connect()
        
        # Authenticate
        if not client.authenticate(TOKEN):
            print("Failed to authenticate!")
            return
        
        # Send telemetry
        telemetry = {
            "temperature": 25.5,
            "humidity": 60,
            "pressure": 1013.25
        }
        client.send_telemetry(telemetry)
        
        # Send attributes
        attributes = {
            "model": "BinaryDevice",
            "firmwareVersion": "1.0.0",
            "protocol": "binary"
        }
        client.send_attributes(attributes)
        
        # Send telemetry periodically
        print("\n=== Sending telemetry every 5 seconds (Ctrl+C to stop) ===\n")
        import random
        
        count = 0
        while True:
            count += 1
            telemetry = {
                "temperature": round(20 + random.random() * 10, 2),
                "humidity": round(50 + random.random() * 20, 2),
                "sequence": count
            }
            
            client.send_telemetry(telemetry)
            time.sleep(5)
        
    except KeyboardInterrupt:
        print("\n\n→ Stopping...")
    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.disconnect()

if __name__ == "__main__":
    main()

