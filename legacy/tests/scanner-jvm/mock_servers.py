import socket,threading,time

def serve(port,handler):
 s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(('127.0.0.1',port)); s.listen()
 def loop():
  while True:
   try:c,a=s.accept(); threading.Thread(target=handler,args=(c,),daemon=True).start()
   except:break
 threading.Thread(target=loop,daemon=True).start()
 return s

def banner(data):
 def h(c):
  try:c.sendall(data); time.sleep(.2)
  finally:c.close()
 return h

def rdp(c):
 try:
  c.recv(128); c.sendall(bytes.fromhex('030000130ed00000123400020008000000000000'))
 finally:c.close()

def http(c):
 try:
  c.recv(2048); c.sendall(b'HTTP/1.0 200 OK\r\nServer: MockHTTP\r\nContent-Length: 0\r\n\r\n')
 finally:c.close()

def rtsp(c):
 try:
  c.recv(2048); c.sendall(b'RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: MockRTSP\r\n\r\n')
 finally:c.close()

def sip(c):
 try:
  c.recv(4096); c.sendall(b'SIP/2.0 200 OK\r\nServer: MockSIP\r\nContent-Length: 0\r\n\r\n')
 finally:c.close()

def mqtt(c):
 try:
  c.recv(4096); c.sendall(bytes([0x20,0x02,0x00,0x00]))
 finally:c.close()

def memcached(c):
 try:
  c.recv(1024); c.sendall(b'VERSION 1.6.30\r\n')
 finally:c.close()

def amqp(c):
 try:
  c.recv(128); c.sendall(b'AMQP\x00\x00\x09\x01')
 finally:c.close()

def udpxy(c):
 try:
  c.recv(2048); body=b'<html><title>udpxy status</title><body>udpxy 1.0 active clients multicast</body></html>'
  c.sendall(b'HTTP/1.0 200 OK\r\nServer: udpxy/1.0\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
 finally:c.close()

servers=[
 serve(22022,banner(b'SSH-2.0-OpenSSH_9.9\r\n')),
 serve(22025,banner(b'220 mock ESMTP ready\r\n')),
 serve(23390,rdp), serve(28080,http), serve(28554,rtsp), serve(25060,sip),
 serve(21883,mqtt), serve(21211,memcached), serve(25672,amqp), serve(24022,udpxy)
]
print('READY',flush=True)
while True:time.sleep(1)
