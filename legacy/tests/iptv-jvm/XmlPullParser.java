package org.xmlpull.v1;
import java.io.Reader;
public interface XmlPullParser {
 int END_DOCUMENT=1,START_TAG=2,END_TAG=3,TEXT=4;
 void setInput(Reader r) throws Exception; int getEventType() throws Exception; int next() throws Exception; String getName(); String getAttributeValue(String ns,String name); String getText();
}
