from http.server import BaseHTTPRequestHandler,HTTPServer

def packet(pid,section,cc=0):
    h=bytes([0x47,0x40|((pid>>8)&0x1f),pid&0xff,0x10|(cc&0xf)])
    payload=b'\x00'+section
    return h+payload+b'\xff'*(188-len(h)-len(payload))

pat=bytes([0x00,0xB0,0x0D,0x00,0x01,0xC1,0x00,0x00,0x00,0x01,0xF0,0x00,0,0,0,0])
pmt=bytes([0x02,0xB0,0x17,0x00,0x01,0xC1,0,0,0xE1,0x00,0xF0,0x00,
           0x1B,0xE1,0x00,0xF0,0x00, 0x0F,0xE1,0x01,0xF0,0x00, 0,0,0,0])
provider=b'Test'; name=b'News HD'
desc=bytes([0x48,14,0x01,len(provider)])+provider+bytes([len(name)])+name
service=bytes([0x00,0x01,0xFC,0x80,0x10])+desc
section_len=8+len(service)+4
sdt=bytes([0x42,0xB0|((section_len>>8)&0x0f),section_len&0xff,0x00,0x01,0xC1,0,0,0x00,0x01,0xFF])+service+bytes(4)
stream=b''
for i in range(80):
    stream+=packet(0x0000,pat,i%16)+packet(0x1000,pmt,i%16)+packet(0x0011,sdt,i%16)

class H(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path.startswith('/status'):
   body=b'<html>udpxy 1.0 active clients multicast</html>'
   self.send_response(200); self.send_header('Server','udpxy/1.0'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
  else:
   self.send_response(200); self.send_header('Content-Type','video/MP2T'); self.send_header('Content-Length',str(len(stream))); self.end_headers(); self.wfile.write(stream)
 def log_message(self,*a): pass
print('READY',flush=True)
HTTPServer(('127.0.0.1',28999),H).serve_forever()
