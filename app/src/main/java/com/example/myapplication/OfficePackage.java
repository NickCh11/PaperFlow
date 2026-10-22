package com.example.myapplication;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Bounded OOXML package access. No archive entry is extracted or external relationship fetched. */
final class OfficePackage implements AutoCloseable {
    final ZipFile zip;private long bytes;
    OfficePackage(File file)throws IOException{zip=new ZipFile(file);if(zip.size()>20000){zip.close();throw new IOException(UiText.get(R.string.ui_office_package_has_too_many_parts));}}
    byte[] read(String name)throws Exception{ZipEntry e=zip.getEntry(name);if(e==null)return null;if(e.getSize()>48L*1024*1024)throw new IOException(UiText.get(R.string.ui_office_part_is_too_large));try(InputStream in=zip.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){bytes+=n;if(out.size()+n>48*1024*1024||bytes>256L*1024*1024)throw new IOException(UiText.get(R.string.ui_office_package_expands_beyond_the_supported_size));out.write(b,0,n);}return out.toByteArray();}}
    Element xml(String name)throws Exception{byte[] data=read(name);if(data==null)return null;String text=new String(data,StandardCharsets.UTF_8);if(text.indexOf('\0')>=0||text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||text.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException(UiText.get(R.string.ui_unsupported_xml_declaration));DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));return b.parse(new ByteArrayInputStream(data)).getDocumentElement();}
    Map<String,String> relationships(String part)throws Exception{int slash=part.lastIndexOf('/');String rel=(slash<0?"":part.substring(0,slash+1))+"_rels/"+part.substring(slash+1)+".rels";Map<String,String> links=new HashMap<>();Element root=xml(rel);if(root!=null)for(Element r:children(root,null)){if("External".equals(attr(r,"TargetMode")))continue;String target=attr(r,"Target");String resolved=target.startsWith("/")?target.substring(1):new java.net.URI((slash<0?"":part.substring(0,slash+1))+target).normalize().getPath();if(!resolved.startsWith("../"))links.put(attr(r,"Id"),resolved);}return links;}
    static String attr(Element e,String name){if(e==null)return "";NamedNodeMap attrs=e.getAttributes();for(int i=0;i<attrs.getLength();i++){Node n=attrs.item(i);if(name.equals(n.getLocalName())||name.equals(n.getNodeName()))return n.getNodeValue();}return "";}
    static float number(Element e,String attr,float fallback){try{return Float.parseFloat(attr(e,attr));}catch(Exception ignored){return fallback;}}
    static ArrayList<Element> children(Element root,String name){ArrayList<Element> result=new ArrayList<>();if(root!=null)for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&(name==null||name.equals(n.getLocalName())))result.add((Element)n);return result;}
    static Element child(Element root,String name){ArrayList<Element> values=children(root,name);return values.isEmpty()?null:values.get(0);}
    static Element first(Element root,String name){if(root==null)return null;NodeList values=root.getElementsByTagNameNS("*",name);return values.getLength()==0?null:(Element)values.item(0);}
    static String text(Element root,String tag){StringBuilder s=new StringBuilder();if(root!=null){NodeList nodes=root.getElementsByTagNameNS("*",tag);for(int i=0;i<nodes.getLength();i++)s.append(nodes.item(i).getTextContent());}return s.toString();}
    static String escape(String value){StringBuilder clean=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\n'||c=='\r'||c=='\t'||c>=32)clean.append(c);}return clean.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    static void entry(ZipOutputStream out,String path,String xml)throws IOException{entry(out,path,xml.getBytes(StandardCharsets.UTF_8));}
    static void entry(ZipOutputStream out,String path,byte[] bytes)throws IOException{out.putNextEntry(new ZipEntry(path));out.write(bytes);out.closeEntry();}
    @Override public void close()throws IOException{zip.close();}
}
