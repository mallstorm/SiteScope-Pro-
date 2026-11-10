from __future__ import annotations
class EvidenceBoundAssistant:
    def build_plan(self,audit:dict)->dict:
        rank={'critical':0,'high':1,'medium':2,'low':3,'info':4}
        issues=sorted(audit.get('issues',[]),key=lambda x:rank.get(x.get('severity','info'),9))
        tasks=[]
        for n,i in enumerate(issues,1):
            tasks.append({'order':n,'title':i.get('title'),'url':i.get('affected_url'),'evidence':i.get('evidence'),'action':i.get('recommendation'),'source_rule':i.get('code')})
        return {'disclaimer':'План построен только по фактическим данным аудита; гарантии роста позиций не даются.','tasks':tasks}
