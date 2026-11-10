from __future__ import annotations
from dataclasses import dataclass,asdict
from datetime import datetime,timezone
import hashlib,json
@dataclass
class Snapshot:
    target:str; kind:str; observed_at:str; fingerprint:str; payload:dict
    def dict(self): return asdict(self)
def make_snapshot(target,kind,payload):
    raw=json.dumps(payload,sort_keys=True,ensure_ascii=False).encode()
    return Snapshot(target,kind,datetime.now(timezone.utc).isoformat(),hashlib.sha256(raw).hexdigest(),payload)
def compare(old:dict,new:dict):
    changes=[]
    keys=sorted(set(old)|set(new))
    for k in keys:
        if old.get(k)!=new.get(k): changes.append({'field':k,'before':old.get(k),'after':new.get(k)})
    return changes
