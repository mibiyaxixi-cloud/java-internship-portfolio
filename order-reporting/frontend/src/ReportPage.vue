<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { api, downloadCsv } from './api'
import ReportChart from './ReportChart.vue'
const props=defineProps({stores:Array,me:Object})
const emit=defineEmits(['detail','expired'])
// Date values are Shanghai calendar days, independent of the browser's time zone.
const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date())
const earlier=new Date(`${today}T00:00:00+08:00`);earlier.setTime(earlier.getTime()-6*86400000)
const first=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(earlier)
const form=ref({from:first,to:today,storeId:''}), applied=ref(null), data=ref(null), busy=ref(false), error=ref('')
const drill=ref(null), drillSection=ref(null), lines=ref(null), page=ref(1), history=ref([]), historyError=ref('')
const money=n=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(n/100)
const when=t=>t?.replace('T',' ')
const eventLabel=t=>t==='PAYMENT'?'支付':'退款'
function query(values){return new URLSearchParams(Object.entries(values).filter(([,v])=>v!==''&&v!=null)).toString()}
async function run(fn){if(busy.value)return;busy.value=true;error.value='';try{await fn()}catch(e){error.value=e.message;if(e.status===401)emit('expired')}finally{busy.value=false}}
async function refresh(){await run(async()=>{const f={...form.value};data.value=null;drill.value=null;lines.value=null;const result=await api(`/reports?${query(f)}`);applied.value=f;data.value=result})}
async function demo(){form.value={...form.value,from:'2026-09-30',to:'2026-10-01'};await refresh()}
async function getLines(){lines.value=null;lines.value=await api(`/reports/lines?${query({...drill.value.filters,page:page.value,size:20})}`)}
async function openDrill(extra={},label='全部资金事件'){await run(async()=>{drill.value={filters:{...applied.value,eventType:'',...extra},label};page.value=1;await getLines();await nextTick();drillSection.value?.scrollIntoView({behavior:'smooth',block:'start'})})}
function eventFor(series){return series==='销售额'?'PAYMENT':series==='退款额'?'REFUND':''}
function daySelect(e){const d=data.value.daily[e.index];if(d)openDrill({from:d.day,to:d.day,eventType:eventFor(e.series)},`${d.day} · ${e.series||'全部事件'}`)}
function storeSelect(e){const s=data.value.stores[e.index];if(s)openDrill({storeId:s.storeId,eventType:eventFor(e.series)},`${s.storeName} · ${e.series||'全部事件'}`)}
function productSelect(e){const p=data.value.products[e.index];if(p)openDrill({productId:p.productId},`${p.productName} · 商品资金事件`)}
async function changeEvent(){await run(async()=>{page.value=1;await getLines()})}
async function next(delta){await run(async()=>{page.value+=delta;await getLines()})}
async function exportLines(){await run(async()=>{await downloadCsv(`/reports/lines/export?${query(drill.value.filters)}`,'report-event-lines.csv');history.value=await api('/exports')})}
const base={animation:false,color:['#317666','#d39955','#53799f'],aria:{enabled:true},tooltip:{trigger:'axis',renderMode:'richText'},legend:{bottom:0},grid:{top:35,bottom:55,left:65,right:25},yAxis:{type:'value',name:'元'},xAxis:{type:'category'},series:[]}
function option(rows,key,type){return {...base,xAxis:{type:'category',data:rows.map(r=>r[key]),axisLabel:{hideOverlap:true}},series:[['销售额','salesCents'],['退款额','refundCents'],['净销售额','netCents']].map(([name,field])=>({name,type,data:rows.map(r=>r[field]/100),symbolSize:8,smooth:false,barMaxWidth:28}))}}
const dailyOption=computed(()=>option(data.value?.daily||[],'day','line'))
const storeOption=computed(()=>option(data.value?.stores||[],'storeName','bar'))
const productOption=computed(()=>({...base,legend:{show:false},xAxis:{type:'category',data:(data.value?.products||[]).map(r=>r.productName),axisLabel:{hideOverlap:true}},series:[{name:'净销售额',type:'bar',barMaxWidth:32,data:(data.value?.products||[]).map(r=>r.netCents/100)}]}))
onMounted(()=>{refresh();api('/exports').then(r=>history.value=r).catch(e=>historyError.value=e.message)})
</script>

<template>
  <div class="title-row"><div><span class="eyebrow">BUSINESS REPORT</span><h1>经营报表</h1><p class="muted">从资金事件看经营，再下钻核对每一笔原始订单。</p></div><span class="scope-tag">{{me.role==='ADMIN'?'全部门店权限':stores[0]?.name}}</span></div>
  <p v-if="error" class="error" role="alert">{{error}}</p>
  <form class="panel filter-row" @submit.prevent="refresh"><label>事件开始日期<input v-model="form.from" type="date" required :disabled="busy"></label><label>事件结束日期<input v-model="form.to" type="date" required :disabled="busy"></label><label>门店<select v-model="form.storeId" :disabled="busy"><option value="">{{me.role==='ADMIN'?'全部门店':'本店'}}</option><option v-for="s in stores" :value="s.id" :key="s.id">{{s.name}}</option></select></label><button class="primary" :disabled="busy">{{busy?'处理中…':'查询报表'}}</button><button type="button" :disabled="busy" @click="demo">演示日期</button></form>
  <p class="notice">上海时间：销售计在支付日，退款计在退款日；净额＝销售−退款，可为负。取消订单不计入。报表日期与订单台账的“下单日期”不同。</p>
  <template v-if="data">
    <div class="report-period"><b>{{data.from}} — {{data.to}}</b><span> · {{stores.find(s=>String(s.id)===String(applied.storeId))?.name||(me.role==='ADMIN'?'全部门店':'本店')}} · 已应用筛选</span><button class="text-button" :disabled="busy" @click="openDrill()">查看全部资金明细 →</button></div>
    <div class="report-kpis"><div class="panel"><span>销售额</span><strong>{{money(data.totals.salesCents)}}</strong><small>{{data.totals.paidOrders}} 笔支付</small></div><div class="panel"><span>退款额</span><strong>{{money(data.totals.refundCents)}}</strong><small>{{data.totals.refundedOrders}} 笔全额退款</small></div><div class="panel"><span>净销售额</span><strong :class="{negative:data.totals.netCents<0}">{{money(data.totals.netCents)}}</strong><small>按事件日期计算</small></div></div>
    <p v-if="!data.totals.paidOrders&&!data.totals.refundedOrders" class="empty panel">此范围没有支付或退款事件。可调整日期，或点击“演示日期”核对已导入样例。</p>
    <section class="panel"><div class="panel-heading"><h3>每日销售趋势</h3><small>点击图中数据点下钻；金额单位：元</small></div><ReportChart :option="dailyOption" label="每日销售、退款与净销售趋势，下方有相同数据表" @select="daySelect"/><details><summary>查看每日数据表</summary><div class="table-scroll"><table><thead><tr><th>事件日期</th><th>销售额</th><th>退款额</th><th>净额</th><th>支付 / 退款笔数</th><th></th></tr></thead><tbody><tr v-for="(d,i) in data.daily" :key="d.day"><td>{{d.day}}</td><td>{{money(d.salesCents)}}</td><td>{{money(d.refundCents)}}</td><td>{{money(d.netCents)}}</td><td>{{d.paidOrders}} / {{d.refundedOrders}}</td><td><button class="text-button" :disabled="busy" @click="daySelect({index:i})">日明细</button></td></tr></tbody></table></div></details></section>
    <div class="report-grid"><section class="panel"><div class="panel-heading"><h3>门店对比</h3><small>按净销售额排序</small></div><ReportChart :option="storeOption" label="门店销售、退款与净额对比" @select="storeSelect"/><div class="table-scroll"><table><thead><tr><th>门店</th><th>销售</th><th>退款</th><th>净额</th><th></th></tr></thead><tbody><tr v-for="(s,i) in data.stores" :key="s.storeId"><td>{{s.storeName}}</td><td>{{money(s.salesCents)}}</td><td>{{money(s.refundCents)}}</td><td>{{money(s.netCents)}}</td><td><button class="text-button" :disabled="busy" @click="storeSelect({index:i})">门店明细</button></td></tr></tbody></table></div></section>
    <section class="panel"><div class="panel-heading"><h3>商品排行</h3><small>净销售额前 {{data.productLimit}} 名 · 同额按商品 ID</small></div><ReportChart :option="productOption" label="商品净销售额排行" @select="productSelect"/><div class="table-scroll"><table><thead><tr><th>商品</th><th>售出 / 退回件数</th><th>销售 / 退款</th><th>净额</th><th></th></tr></thead><tbody><tr v-for="(p,i) in data.products" :key="p.productId"><td>{{p.productName}}</td><td>{{p.soldQuantity}} / {{p.refundedQuantity}}</td><td>{{money(p.salesCents)}} / {{money(p.refundCents)}}</td><td>{{money(p.netCents)}}</td><td><button class="text-button" :disabled="busy" @click="productSelect({index:i})">商品明细</button></td></tr></tbody></table></div><p v-if="!data.products.length" class="footnote">暂无商品事件</p></section></div>
  </template>
  <section v-if="drill" ref="drillSection" class="panel" aria-label="资金事件明细"><div class="panel-heading"><div><h3>{{drill.label}}</h3><small>{{drill.filters.from}} — {{drill.filters.to}} · 每行对应一个商品的支付或退款事件</small></div><button :disabled="busy||!lines" @click="exportLines">导出此范围 CSV</button></div><label class="event-selector">事件类型<select v-model="drill.filters.eventType" @change="changeEvent" :disabled="busy"><option value="">全部事件</option><option value="PAYMENT">支付</option><option value="REFUND">退款</option></select></label><template v-if="lines"><div class="table-scroll"><table><thead><tr><th>事件时间</th><th>事件</th><th>订单 / 门店</th><th>商品 / 明细行</th><th>数量</th><th>销售</th><th>退款</th><th>净额</th><th></th></tr></thead><tbody><tr v-for="r in lines.items" :key="`${r.orderId}-${r.eventType}-${r.lineNo}`"><td>{{when(r.eventAt)}}</td><td><span class="badge" :class="r.eventType==='PAYMENT'?'PAID':'REFUNDED'">{{eventLabel(r.eventType)}}</span></td><td>{{r.externalId}}<small>{{r.storeName}}</small></td><td>{{r.productName}}<small>行 {{r.lineNo}}</small></td><td>{{r.quantity}}</td><td>{{money(r.salesCents)}}</td><td>{{money(r.refundCents)}}</td><td>{{money(r.netCents)}}</td><td><button class="text-button" :disabled="busy" @click="emit('detail',r.orderId)">原始订单</button></td></tr></tbody></table></div><p v-if="!lines.items.length" class="empty">此筛选下没有资金明细。</p><div class="pagination"><span>第 {{lines.page}} 页 · 共 {{lines.total}} 条事件明细</span><div><button :disabled="busy||lines.page<=1" @click="next(-1)">上一页</button><button :disabled="busy||lines.page*20>=lines.total" @click="next(1)">下一页</button></div></div></template><p class="footnote">导出包含当前明细筛选下的全部行，最多 10,000 行，并非仅当前页。退款列为正值；净额已减退款。同笔订单可同时有支付和退款事件。</p></section>
  <details class="panel"><summary>我的最近导出记录</summary><p class="footnote">记录文件生成成功，不代表浏览器已保存；仅当前账号可见。</p><table><thead><tr><th>生成时间</th><th>类型</th><th>行数</th></tr></thead><tbody><tr v-for="h in history" :key="h.id"><td>{{when(h.created_at)}}</td><td>{{h.kind==='EVENT_LINES'?'资金事件明细':'订单台账'}}</td><td>{{h.row_count}}</td></tr></tbody></table><p v-if="historyError" class="error" role="alert">导出记录加载失败：{{historyError}}</p><p v-else-if="!history.length" class="footnote">暂无导出记录</p></details>
</template>
