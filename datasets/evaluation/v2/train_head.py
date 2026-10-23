"""Fit a frozen-encoder logistic head using training.txt ONLY; report old diagnostic development metrics.
Run from repository root with .model-cache/verify-env/Scripts/python.exe datasets/evaluation/v2/train_head.py
Requires numpy, tokenizers, onnxruntime. No holdout inputs are read by this program.
"""
import json, hashlib
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
root=Path(__file__).resolve().parents[3]
p=root/'datasets/evaluation/v2'
tok=Tokenizer.from_file(str(root/'.model-cache/tokenizer.json'))
tok.enable_truncation(max_length=128)
session=ort.InferenceSession(str(root/'app/src/main/assets/semantic/model.onnx'),providers=['CPUExecutionProvider'])
def embed(text):
    ids=np.array([tok.encode(text[:8192]).ids],dtype=np.int64)
    inputs={'input_ids':ids,'attention_mask':np.ones_like(ids),'token_type_ids':np.zeros_like(ids)}
    v=session.run(None,{x.name:inputs[x.name] for x in session.get_inputs()})[0][0,0].astype(np.float64)
    return v/np.linalg.norm(v)
train=[]
for line in (p/'training.txt').read_text(encoding='utf-8-sig').splitlines():
    label,title,text=line.split('|',2);train.append((int(label),title+'\n'+text))
x=np.stack([embed(t) for _,t in train]);y=np.array([label==2 for label,_ in train],dtype=np.float64)
# Fixed hyperparameters, no random initialization, no holdout-dependent selection.
w=np.zeros(x.shape[1]);b=0.;center=x.mean(axis=0);xc=x-center
for _ in range(2500):
    pred=1/(1+np.exp(-np.clip(xc@w+b,-40,40)))
    err=pred-y
    w-=2.0*(xc.T@err/len(y)+0.001*w)
    b-=2.0*err.mean()
bias=float(b-center@w)
head=dict(version=1,encoder='bge-small-zh-v1.5-int8-75c43b0-v1',dimension=512,weights=w.tolist(),bias=bias,normal_max=0.35,spam_min=0.65,training_rows=len(train),training_sha256=hashlib.sha256((p/'training.txt').read_bytes()).hexdigest(),provenance='Agent-authored synthetic examples; frozen encoder, logistic classification head; scores uncalibrated')
(root/'app/src/main/assets/semantic/classifier.json').write_text(json.dumps(head,indent=2)+'\n',encoding='utf-8')
(p/'classifier.json').write_text(json.dumps(head,indent=2)+'\n',encoding='utf-8')
print('Training accuracy:',float(np.mean((x@w+bias>=0)==y)))
# Old public diagnostic = development only, never train on its texts or labels.
dev=json.loads((root/'datasets/evaluation/diagnostic-v1.json').read_text(encoding='utf-8'))['tests']
rows=[]
for row in dev:
    score=float(1/(1+np.exp(-np.clip(embed(row['title']+'\n'+row['text'])@w+bias,-40,40))))
    label=2 if score>=0.65 else 1 if score<=0.35 else 0
    if row['package'] in ['com.tencent.mm','com.tencent.mobileqq','com.whatsapp','org.telegram.messenger'] and label==2: label=0
    rows.append(dict(id=row['id'],expected=row['label'],predicted=label,score=score))
(p/'development-head.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
print('Development matrix normal/spam by normal/spam/uncertain:',[[sum(r['expected']==a and r['predicted']==b for r in rows) for b in [1,2,0]] for a in [1,2]])
