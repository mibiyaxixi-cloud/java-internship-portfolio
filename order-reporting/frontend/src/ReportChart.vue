<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { init, use } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, AriaComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent, SVGRenderer])
const props=defineProps({ option:Object, label:String })
const emit=defineEmits(['select'])
const host=ref(null)
let chart, observer
onMounted(()=>{
  chart=init(host.value,null,{renderer:'svg'})
  chart.setOption(props.option)
  chart.on('click',e=>emit('select',{index:e.dataIndex,series:e.seriesName}))
  observer=new ResizeObserver(()=>chart.resize())
  observer.observe(host.value)
})
watch(()=>props.option,option=>chart?.setOption(option,{notMerge:true}))
onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="host" class="report-chart" role="img" :aria-label="label"></div></template>
