package pro.sitescope.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity:ComponentActivity(){ override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{SiteScopeApp()}}} }
@Composable fun SiteScopeApp(vm:MainViewModel=androidx.lifecycle.viewmodel.compose.viewModel()){
 val s by vm.state.collectAsStateWithLifecycle()
 Scaffold(topBar={TopAppBar(title={Text("SiteScope Pro")})}){pad->
  LazyColumn(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Text("SEO-аудит ресурса",style=MaterialTheme.typography.headlineSmall)}
   item{OutlinedTextField(value=s.url,onValueChange=vm::setUrl,label={Text("https://example.com")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
   item{Button(onClick=vm::runAudit,enabled=!s.loading,modifier=Modifier.fillMaxWidth()){Text(if(s.loading)"Анализ…" else "Запустить аудит")}}
   s.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
   s.audit?.let{a-> item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Оценка: ${a.score}/100",style=MaterialTheme.typography.headlineMedium);Text("Страниц: ${a.pages.size} · проблем: ${a.issues.size}")}}}; items(a.issues){i->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(i.title,style=MaterialTheme.typography.titleMedium);Text(i.severity.uppercase());Text(i.why);HorizontalDivider(Modifier.padding(vertical=8.dp));Text("Исправление: ${i.recommendation}")}}}}
  }
 }
}
