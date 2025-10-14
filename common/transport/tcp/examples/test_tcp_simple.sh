#!/bin/bash
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

# Simple TCP Transport Test Script
# This script uses netcat (nc) to test TCP transport connection

HOST=${1:-localhost}
PORT=${2:-8883}
DEVICE_TOKEN=${3:-YOUR_DEVICE_TOKEN}

echo "Testing TCP Transport on $HOST:$PORT"
echo "Device Token: $DEVICE_TOKEN"
echo ""

# Create a named pipe for bi-directional communication
PIPE=/tmp/tcp_test_$$
mkfifo $PIPE

# Start background process to read responses
(
    while IFS= read -r line; do
        echo "← Response: $line"
    done < $PIPE
) &
READER_PID=$!

# Connect and send commands
(
    echo "→ Sending: AUTH:$DEVICE_TOKEN" >&2
    echo "AUTH:$DEVICE_TOKEN"
    sleep 1
    
    echo "→ Sending telemetry..." >&2
    echo 'TELEMETRY:{"temperature":25.5,"humidity":60}'
    sleep 1
    
    echo "→ Sending attributes..." >&2
    echo 'ATTRIBUTES:{"model":"TestDevice","version":"1.0"}'
    sleep 1
    
) | nc $HOST $PORT > $PIPE

# Cleanup
kill $READER_PID 2>/dev/null
rm -f $PIPE

echo ""
echo "Test completed"

