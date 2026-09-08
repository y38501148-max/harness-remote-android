(() => {
  if (window !== window.top || !window.HarnessNative) return;
  window.__HARNESS_NATIVE__ = true;
  const pending = new Map(), sockets = new Map(); let sequence = 0;
  const send = value => HarnessNative.postMessage(JSON.stringify(value));
  const encode = bytes => { let s=''; for(let i=0;i<bytes.length;i+=16384) s+=String.fromCharCode(...bytes.subarray(i,i+16384)); return btoa(s); };
  const decode = s => Uint8Array.from(atob(s), c=>c.charCodeAt(0));
  HarnessNative.onmessage = ({data}) => {
    const m=JSON.parse(data), p=pending.get(m.id), ws=sockets.get(m.id);
    if (m.type==='http-head' && p) { p.meta=m; return; }
    if (m.type==='http-chunk' && p) { p.chunks.push(decode(m.body)); return; }
    if (m.type==='http-end' && p) { pending.delete(m.id); p.cleanup(); const r=new Response([204,205,304].includes(p.meta.status)?null:new Blob(p.chunks),{status:p.meta.status,headers:p.meta.headers,statusText:p.meta.message}); Object.defineProperty(r,'url',{value:p.url}); p.resolve(r); return; }
    if (m.type==='error' && p) { pending.delete(m.id);p.cleanup();p.reject(new TypeError(m.error));return; }
    if (!ws) return;
    if(m.type==='ws-open') { ws.readyState=1;ws.protocol=m.protocol||''; ws.dispatchEvent(new Event('open')); }
    if(m.type==='ws-message') ws.dispatchEvent(new MessageEvent('message',{data:m.binary?(ws.binaryType==='arraybuffer'?decode(m.body).buffer:new Blob([decode(m.body)])):m.body}));
    if(m.type==='ws-close'||m.type==='error') { ws.readyState=3;sockets.delete(m.id);if(m.type==='error')ws.dispatchEvent(new Event('error'));ws.dispatchEvent(new CloseEvent('close',{code:m.code||1006,reason:m.reason||'',wasClean:m.clean===true})); }
  };
  window.fetch=async function(input,options) {
    const request=new Request(input,options), url=new URL(request.url);
    if(url.origin!==location.origin) throw new TypeError('仅允许访问已配对电脑');
    request.signal.throwIfAborted();
    const bytes=request.body?new Uint8Array(await request.arrayBuffer()):new Uint8Array();
    if(bytes.length>32*1024*1024) throw new TypeError('请求超过 32 MiB 限制');
    return new Promise((resolve,reject)=>{
      const id=String(++sequence), abort=()=>{send({type:'cancel',id});pending.delete(id);reject(new DOMException('Aborted','AbortError'));};
      pending.set(id,{resolve,reject,url:request.url,chunks:[],cleanup:()=>request.signal.removeEventListener('abort',abort)});
      request.signal.addEventListener('abort',abort,{once:true});
      if(request.signal.aborted){abort();return;}
      send({type:'http-begin',id,url:request.url,method:request.method,headers:Object.fromEntries(request.headers)});
      for(let i=0;i<bytes.length;i+=65536) send({type:'http-body',id,body:encode(bytes.subarray(i,i+65536))});
      send({type:'http-send',id});
    });
  };
  class NativeSocket extends EventTarget {
    static CONNECTING=0; static OPEN=1;static CLOSING=2;static CLOSED=3;
    CONNECTING=0;OPEN=1;CLOSING=2;CLOSED=3;readyState=0;binaryType='blob';bufferedAmount=0;protocol='';extensions='';
    constructor(url,protocols=[]) {super();const u=new URL(url,location.href);if(u.protocol!=='wss:'||u.host!==location.host)throw new DOMException('Untrusted socket','SecurityError');this.url=u.href;this.id=String(++sequence);sockets.set(this.id,this);send({type:'ws-open',id:this.id,url:this.url,protocols:typeof protocols==='string'?[protocols]:protocols});}
    send(data) {if(this.readyState!==1)throw new DOMException('Socket not open','InvalidStateError');if(typeof data==='string')send({type:'ws-send',id:this.id,body:data,binary:false});else if(data instanceof Blob)data.arrayBuffer().then(b=>this.send(b));else {const b=ArrayBuffer.isView(data)?new Uint8Array(data.buffer,data.byteOffset,data.byteLength):new Uint8Array(data);send({type:'ws-send',id:this.id,body:encode(b),binary:true});}}
    close(code=1000,reason=''){if(code!==1000&&(code<3000||code>4999))throw new DOMException('Invalid close code','InvalidAccessError');if(new TextEncoder().encode(reason).length>123)throw new SyntaxError('Close reason too long');if(this.readyState>=2)return;this.readyState=2;send({type:'ws-close',id:this.id,code,reason});}
  }
  for(const name of ['open','message','close','error'])Object.defineProperty(NativeSocket.prototype,'on'+name,{get(){return this['_on'+name]||null;},set(value){if(this['_on'+name])this.removeEventListener(name,this['_on'+name]);this['_on'+name]=typeof value==='function'?value:null;if(this['_on'+name])this.addEventListener(name,value);}});
  window.WebSocket=NativeSocket;
  document.addEventListener('click',event=>{
    const link=event.target.closest?.('a[download]');
    if(!link || !event.isTrusted)return;
    const url=new URL(link.href,location.href);if(url.origin!==location.origin)return;
    event.preventDefault();event.stopPropagation();send({type:'download',id:String(++sequence),url:url.href,name:link.download||''});
  },true);
})();
