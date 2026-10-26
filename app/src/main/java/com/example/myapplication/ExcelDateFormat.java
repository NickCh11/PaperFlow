package com.example.myapplication;

import java.time.LocalDate;
import java.util.*;

final class ExcelDateFormat {
    private static final Map<Integer,String> BUILTIN=new HashMap<>();
    static{String[] formats={"m/d/yy","d-mmm-yy","d-mmm","mmm-yy","h:mm AM/PM","h:mm:ss AM/PM","h:mm","h:mm:ss","m/d/yy h:mm"};for(int i=0;i<formats.length;i++)BUILTIN.put(14+i,formats[i]);BUILTIN.put(45,"mm:ss");BUILTIN.put(46,"[h]:mm:ss");BUILTIN.put(47,"mmss.0");}
    // Scan formatting literals once; unmatched brackets must not trigger a
    // regex search from every opening bracket in an imported spreadsheet.
    private static String formatTokens(String pattern){
        StringBuilder tokens=new StringBuilder();
        for(int i=0;i<pattern.length();){
            char c=pattern.charAt(i);
            if(c==';')break;
            if(c=='"'){int end=pattern.indexOf('"',i+1);if(end<0)break;i=end+1;continue;}
            if(c=='\\'){i=Math.min(pattern.length(),i+2);continue;}
            if(c=='['){
                int end=pattern.indexOf(']',i+1);if(end<0)return "";
                String value=pattern.substring(i+1,end).toLowerCase(Locale.ROOT);
                if(!value.isEmpty()&&value.chars().allMatch(ch->ch=='h'||ch=='m'||ch=='s'))tokens.append('[').append(value).append(']');
                i=end+1;continue;
            }
            tokens.append(Character.toLowerCase(c));i++;
        }
        return tokens.toString();
    }
    static String render(double serial,int id,String custom,boolean epoch1904){
        String pattern=custom.isEmpty()?BUILTIN.get(id):custom;if(pattern==null)return null;
        String tokens=formatTokens(pattern);
        if(tokens.chars().noneMatch(c->"ymdhs".indexOf(c)>=0))return null;
        if(!Double.isFinite(serial)||serial<0||serial>2958465)return null;
        java.util.regex.Matcher precision=java.util.regex.Pattern.compile("\\.(0+)").matcher(tokens);int fractionDigits=precision.find()?Math.min(6,precision.group(1).length()):0;
        long factor=(long)Math.pow(10,fractionDigits),days=(long)Math.floor(serial),ticks=Math.round((serial-days)*86400*factor),seconds=ticks/factor,fraction=ticks%factor;if(seconds==86400){days++;seconds=0;}
        LocalDate date=(epoch1904?LocalDate.of(1904,1,1):LocalDate.of(1899,12,days>=60?30:31)).plusDays(days);
        int hour=(int)(seconds/3600),minute=(int)(seconds/60%60),second=(int)(seconds%60);boolean ampm=tokens.contains("am/pm");StringBuilder out=new StringBuilder();boolean time=false;
        for(int i=0;i<pattern.length();){char c=pattern.charAt(i),lower=Character.toLowerCase(c);
            if(c==';')break;
            if(c=='"'){int end=pattern.indexOf('"',i+1);if(end<0)break;out.append(pattern,i+1,end);i=end+1;continue;}
            if(c=='\\'){if(++i<pattern.length())out.append(pattern.charAt(i++));continue;}
            if(c=='_'||c=='*'){i=Math.min(pattern.length(),i+2);continue;}
            if(pattern.regionMatches(true,i,"AM/PM",0,5)){out.append(hour<12?"AM":"PM");i+=5;continue;}
            if(c=='['){int end=pattern.indexOf(']',i);if(end<0)return null;String unit=pattern.substring(i+1,end).toLowerCase(Locale.ROOT);if(unit.matches("h+|m+|s+")){long total=days*86400+seconds;out.append(unit.charAt(0)=='h'?total/3600:unit.charAt(0)=='m'?total/60:total);time=true;}i=end+1;continue;}
            if("ymdhs".indexOf(lower)>=0){int end=i+1;while(end<pattern.length()&&Character.toLowerCase(pattern.charAt(end))==lower)end++;int count=end-i;String next=pattern.substring(end).toLowerCase(Locale.ROOT);boolean minutes=lower=='m'&&(time||next.matches("^[: ]*s.*"));int value;
                switch(lower){case 'y':value=count<=2?date.getYear()%100:date.getYear();break;case 'd':if(count>=3){out.append(date.getDayOfWeek().getDisplayName(count==3?java.time.format.TextStyle.SHORT:java.time.format.TextStyle.FULL,Locale.ROOT));i=end;continue;}value=date.getDayOfMonth();break;case 'm':if(!minutes&&count>=3){out.append(date.getMonth().getDisplayName(count==3?java.time.format.TextStyle.SHORT:java.time.format.TextStyle.FULL,Locale.ROOT));i=end;continue;}value=minutes?minute:date.getMonthValue();break;case 'h':time=true;value=ampm?(hour%12==0?12:hour%12):hour;break;default:time=true;value=second;}
                out.append(count>=2?String.format(Locale.ROOT,"%0"+Math.min(count,4)+"d",value):Integer.toString(value));i=end;continue;
            }
            if(c=='.'&&i+1<pattern.length()&&pattern.charAt(i+1)=='0'){int end=i+1;while(end<pattern.length()&&pattern.charAt(end)=='0')end++;int digits=Math.min(6,end-i-1);out.append('.').append(String.format(Locale.ROOT,"%0"+digits+"d",fraction));i=end;continue;}
            out.append(c);i++;
        }
        return out.toString();
    }
}
