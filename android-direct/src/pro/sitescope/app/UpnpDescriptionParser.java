package pro.sitescope.app;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Read-only parser for UPnP device description documents. */
public final class UpnpDescriptionParser {
    public static final class ServiceInfo {
        public String serviceType="";
        public String serviceId="";
        public String scpdUrl="";
        public String controlUrl="";
        public String eventSubUrl="";
    }
    public static final class Description {
        public String friendlyName = "";
        public String manufacturer = "";
        public String manufacturerUrl = "";
        public String modelDescription = "";
        public String modelName = "";
        public String modelNumber = "";
        public String modelUrl = "";
        public String serialNumber = "";
        public String udn = "";
        public String deviceType = "";
        public String presentationUrl = "";
        public final List<String> services = new ArrayList<String>();
        public final List<ServiceInfo> serviceInfos = new ArrayList<ServiceInfo>();
        public final List<String> icons = new ArrayList<String>();
    }

    private UpnpDescriptionParser() { }

    public static Description parse(InputStream input) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, null);
        Description result = new Description();
        String tag = "";
        ServiceInfo currentService=null;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = local(parser.getName());
                if("service".equals(tag))currentService=new ServiceInfo();
            } else if (event == XmlPullParser.TEXT) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if (value.length() > 0) {
                    if(currentService!=null) assignService(currentService,tag,value);
                    assign(result, tag, value);
                }
            } else if(event==XmlPullParser.END_TAG&&"service".equals(local(parser.getName()))&&currentService!=null){
                if(currentService.serviceType.length()>0)result.serviceInfos.add(currentService);
                currentService=null;
            }
            event = parser.next();
        }
        return result;
    }

    private static void assignService(ServiceInfo s,String tag,String value){
        if("serviceType".equals(tag))s.serviceType=value;
        else if("serviceId".equals(tag))s.serviceId=value;
        else if("SCPDURL".equals(tag))s.scpdUrl=value;
        else if("controlURL".equals(tag))s.controlUrl=value;
        else if("eventSubURL".equals(tag))s.eventSubUrl=value;
    }

    private static void assign(Description result, String tag, String value) {
        if ("friendlyName".equals(tag) && result.friendlyName.length() == 0) result.friendlyName = value;
        else if ("manufacturer".equals(tag) && result.manufacturer.length() == 0) result.manufacturer = value;
        else if ("manufacturerURL".equals(tag) && result.manufacturerUrl.length() == 0) result.manufacturerUrl = value;
        else if ("modelDescription".equals(tag) && result.modelDescription.length() == 0) result.modelDescription = value;
        else if ("modelName".equals(tag) && result.modelName.length() == 0) result.modelName = value;
        else if ("modelNumber".equals(tag) && result.modelNumber.length() == 0) result.modelNumber = value;
        else if ("modelURL".equals(tag) && result.modelUrl.length() == 0) result.modelUrl = value;
        else if ("serialNumber".equals(tag) && result.serialNumber.length() == 0) result.serialNumber = value;
        else if ("UDN".equals(tag) && result.udn.length() == 0) result.udn = value;
        else if ("deviceType".equals(tag) && result.deviceType.length() == 0) result.deviceType = value;
        else if ("presentationURL".equals(tag) && result.presentationUrl.length() == 0) result.presentationUrl = value;
        else if ("serviceType".equals(tag) && !result.services.contains(value)) result.services.add(value);
        else if ("url".equals(tag) && !result.icons.contains(value)) result.icons.add(value);
    }

    private static String local(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
