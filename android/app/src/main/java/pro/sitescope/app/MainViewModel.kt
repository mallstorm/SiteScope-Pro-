package pro.sitescope.app
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pro.sitescope.app.data.*

data class UiState(val url:String="",val loading:Boolean=false,val audit:AuditDto?=null,val error:String?=null)
class MainViewModel(private val api:SiteScopeApi=ApiFactory.create()):ViewModel(){
 private val _state=MutableStateFlow(UiState()); val state:StateFlow<UiState> = _state
 fun setUrl(v:String){_state.value=_state.value.copy(url=v,error=null)}
 fun runAudit(){ val u=_state.value.url.trim(); if(u.isBlank()){_state.value=_state.value.copy(error="Введите адрес сайта");return}; viewModelScope.launch { _state.value=_state.value.copy(loading=true,error=null); runCatching{api.audit(AuditRequest(u))}.onSuccess{_state.value=_state.value.copy(loading=false,audit=it)}.onFailure{_state.value=_state.value.copy(loading=false,error=it.message?:"Ошибка")}}}
}
