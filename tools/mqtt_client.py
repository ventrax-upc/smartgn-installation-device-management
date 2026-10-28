import socket
import struct
import uuid
import json

class SmokeFailure(Exception):
    pass

def require(condition,message):
    if not condition: raise SmokeFailure(message)

def mqtt_variable(value):
    result = bytearray()
    while True:
        byte = value % 128
        value //= 128
        if value:
            byte |= 128
        result.append(byte)
        if not value:
            return bytes(result)


def mqtt_string(value):
    encoded = value.encode()
    return struct.pack("!H", len(encoded)) + encoded


class Mqtt5:
    def __init__(self, host, port, username, password):
        self.socket = socket.create_connection((host, port), timeout=3)
        self.socket.settimeout(3)
        self.packet_id = 0
        client = "smartgn-smoke-" + str(uuid.uuid4())
        connect = mqtt_string("MQTT") + b"\x05\xc2\x00\x3c\x00" + mqtt_string(client) + mqtt_string(username) + mqtt_string(password)
        self.send(0x10, connect)
        header, body = self.read()
        require(header == 0x20 and len(body) >= 3, "Broker did not return an MQTT 5 CONNACK")
        self.reason = body[1]

    def send(self, header, body):
        self.socket.sendall(bytes([header]) + mqtt_variable(len(body)) + body)

    def exact(self, size):
        result = bytearray()
        while len(result) < size:
            value = self.socket.recv(size - len(result))
            if not value:
                raise EOFError()
            result.extend(value)
        return bytes(result)

    def read(self):
        header = self.exact(1)[0]
        size, multiplier = 0, 1
        for index in range(4):
            byte = self.exact(1)[0]
            size += (byte & 127) * multiplier
            if not byte & 128:
                return header, self.exact(size)
            multiplier *= 128
        raise SmokeFailure("Malformed MQTT remaining length")

    def publish(self, topic, payload):
        self.packet_id += 1
        body = mqtt_string(topic) + struct.pack("!H", self.packet_id) + b"\x00" + json.dumps(payload).encode()
        self.send(0x32, body)
        header, ack = self.read()
        if header == 0xE0:
            return ack[0] if ack else 0x80
        require(header == 0x40 and len(ack) >= 2, "Broker did not acknowledge QoS 1 publication")
        require(struct.unpack("!H", ack[:2])[0] == self.packet_id, "MQTT PUBACK identity mismatch")
        return ack[2] if len(ack) > 2 else 0

    def close(self):
        try:
            self.send(0xE0, b"\x00\x00")
        except OSError:
            pass
        self.socket.close()
