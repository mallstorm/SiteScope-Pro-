package pro.sitescope.app;

import org.json.*;

/** One narrowly defined setting, with preview, stale-value checks and durable recovery evidence. */
public final class RouterSettings {
    private RouterSettings() { }
    private static final java.util.concurrent.ConcurrentHashMap<String,Boolean> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    public interface Guard { void check() throws Exception; }
    public interface Journal { void beforeWrite(Plan plan) throws Exception; void applied(Plan plan) throws Exception; default void finished() { } }
    public static final class Plan {
        public final String endpoint,kind,scope,section,before,after;
        public final long time;
        Plan(String endpoint,String kind,String scope,String section,String before,String after,long time){this.endpoint=endpoint;this.kind=kind;this.scope=scope;this.section=section;this.before=before;this.after=after;this.time=time;}
    }
    public static String newName(String value){if(value==null||!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))throw new IllegalArgumentException("Имя: 1–63 латинских буквы, цифры или дефис внутри");return value;}
    static String original(String value){if(value==null||value.isEmpty()||value.length()>64||value.matches("(?s).*[\\p{Cntrl}\\p{Cf}].*"))throw new IllegalArgumentException("Прежнее имя не поддерживается для безопасного возврата");return value;}
    public static Plan preview(RouterApiClient client,RouterStore.Config config,String user,String password,String scope,String target,boolean restoring,Guard guard)throws Exception{
        if(scope==null||scope.isEmpty())throw new IllegalArgumentException("Сеть не определена");
        String after=restoring?original(target):newName(target);
        try{guard.check();client.startSettings(config,user,password);String[] current=client.settingsName(config.kind);guard.check();String before=original(current[1]);if(before.equals(after))throw new IllegalArgumentException("Имя уже совпадает: изменение не требуется");return new Plan(RouterStore.address(config.base,config.allowHttp).toASCIIString(),config.kind,scope,current[0],before,after,System.currentTimeMillis());}
        finally{client.finishSettings();}
    }
    public static void apply(RouterApiClient client,RouterStore.Config config,String user,String password,String scope,Plan plan,Guard guard,Journal journal)throws Exception{
        if(!plan.endpoint.equals(RouterStore.address(config.base,config.allowHttp).toASCIIString())||!plan.kind.equals(config.kind)||!plan.scope.equals(scope)||System.currentTimeMillis()-plan.time<0||System.currentTimeMillis()-plan.time>120000)throw new IllegalArgumentException("Предпросмотр устарел или подключение изменилось: получите его заново");
        String slot=plan.endpoint+"|"+plan.scope;if(ACTIVE.putIfAbsent(slot,true)!=null)throw new IllegalArgumentException("Предыдущая запись ещё завершается: повторите позже");
        try{guard.check();client.startSettings(config,user,password);String[] current=client.settingsName(config.kind);guard.check();if(!current[0].equals(plan.section)||!current[1].equals(plan.before))throw new IllegalArgumentException("Имя или секция изменились после предпросмотра. Запись не выполнена");
            journal.beforeWrite(plan);guard.check();client.settingsSet(config.kind,plan.section,plan.after,guard);
            String[] actual=client.settingsName(config.kind);guard.check();if(!actual[0].equals(plan.section)||!actual[1].equals(plan.after))throw new IllegalStateException("Результат записи не подтверждён. Проверьте журнал и имя роутера");journal.applied(plan);
        }finally{try{client.finishSettings();}finally{ACTIVE.remove(slot);journal.finished();}}
    }
}
