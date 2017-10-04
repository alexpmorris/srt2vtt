// srt2vtt, joins/merges multiple SubRip files into a single WebVTT file
// adds additional formatting, including speaker names as extracted from each .srt file
// Also adds titles (via titles.srt) and creates an HTML transcript of the WebVTT output.
//
// by Alexander Paul Morris, 2017-10-04
// steemit, github, twitter: @alexpmorris

package com.yourika;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Character.isUpperCase;

public class Main {

    static String outputName = "";
    static String outputNameHTML = "";
    static ArrayList<String> inputFileNames = new ArrayList<String>();
    static int speakers = 0;

    static class cueRec {
        String speaker = "";
        String startTime = "";
        String endTime = "";
        String line1 = "";
        String line2 = "";
        String attr = "";
        boolean isHost = false;
        boolean showSpeaker = false;
        boolean isTitle = false;
        int speakerID;
    }

    static ArrayList<cueRec> cueList = new ArrayList<cueRec>();

    static class cueCompare implements Comparator<cueRec>
    {
        public int compare(cueRec a, cueRec b)
        {
            return a.startTime.compareTo(b.startTime);
        }
    }

    static Pattern maxLengthWithWords = Pattern.compile("\\G\\s*(.{1,45})(?=\\s|$)", Pattern.DOTALL);

    public static void processSRT(String fn) {
        String[] fn_arr = fn.split("[\\\\.]");
        String speaker = fn_arr[fn_arr.length-2];
        boolean isHost = cueList.size() == 0;
        boolean isTitles = !isHost && speaker.equals("titles");
        try (Scanner scanner =  new Scanner(Paths.get(fn))) {
            cueRec cue = null;
            while (scanner.hasNextLine()) {
                if (cue == null) cue = new cueRec();
                cue.speaker = speaker;
                cue.isHost = isHost;
                cue.isTitle = isTitles;
                if (!isTitles) cue.speakerID = speakers; else cue.speakerID = 99;
                String line = scanner.nextLine();
                if (line.contains(" --> ")) {
                    line = line.replace(",",".").trim();
                    String[] times = line.split(" --> ");
                    cue.startTime = times[0];
                    cue.endTime = times[1];
                } else if (line.isEmpty()) {
                    if (!cue.endTime.isEmpty()) {
                        int pos = cue.line1.indexOf(":");
                        if (!cue.line2.isEmpty()) cue.line1 = cue.line1 + " " + cue.line2;
                        cue.line2 = "";
                        if (!isTitles) cue.line1 = cue.line1.replaceAll("@[\\w-:]+","");
                        if (cue.line1.length() <= 90) {
                            Matcher match = maxLengthWithWords.matcher(cue.line1);
                            if (match.find()) cue.line1 = match.group(0);
                            if (match.find()) cue.line2 = (match.group(0) + " " + cue.line2).trim();
                        }
                        if (cue.line1.isEmpty() && cue.line2.isEmpty()) {  //skip if cue entry is blank!
                            cue.startTime = "";
                            cue.endTime = "";
                        } else {
                            cueList.add(cue);
                            cue = null;
                        }
                    }
                } else {
                    if ((cue != null) && (!cue.endTime.isEmpty())) {
                        if (cue.line1.isEmpty()) cue.line1 = line.trim();
                            else cue.line2 = line.trim();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        System.out.println("Parsed "+speaker+"!");
        return;
    }

    public static void addAttributes() {
        ArrayList<cueRec> cues = new ArrayList<>();
        String lastSpeaker = "";

        for (cueRec cue: cueList) {
            for (int i = cues.size()-1; i>=0; i--) {
                cueRec tmpCue = cues.get(i);
                if (cue.startTime.compareTo(tmpCue.endTime) >= 0) cues.remove(i);
            }
            if (cue.isTitle) {
                cue.attr = "line 50%";
            } else
            if (cue.isHost) {
                if (cues.size() > 0) cue.attr = "position:20%";
            } else
            switch (cues.size()) {
                case 1: cue.attr = "position:80%"; break;
                case 2: cue.attr = "position:20% line:60%"; break;
                case 3: cue.attr = "position:80% line:60%"; break;
                case 4: cue.attr = "position:20% line:40%"; break;
                case 5: cue.attr = "position:80% line:40%"; break;
                default: cue.attr = ""; break;
            }
            if ((!cue.isTitle) && (!lastSpeaker.equals(cue.speaker))) cue.showSpeaker = true;
            lastSpeaker = cue.speaker;
            cues.add(cue);
        }
    }

    public static void saveWebVTT() {
        try {
            ArrayList<String> vtt = new ArrayList<>();

            vtt.add("WEBVTT\n\n");

            int entries = 0;
            for (cueRec cue: cueList) {
                entries++;
                vtt.add(Integer.toString(entries)+"\n");
                vtt.add(cue.startTime+" --> "+cue.endTime+" "+cue.attr+"\n");
                String color;  //https://en.wikipedia.org/wiki/Web_colors
                switch (cue.speakerID) {
                    case 0: color = "<font color=\"Cyan\">"; break;
                    case 1: color = "<font color=\"Lime\">"; break;
                    case 2: color = "<font color=\"Violet\">"; break;
                    case 3: color = "<font color=\"Yellow\">"; break;
                    case 4: color = "<font color=\"LightCoral\">"; break;
                    case 5: color = "<font color=\"Pink\">"; break;
                    case 6: color = "<font color=\"LightBlue\">"; break;
                    case 7: color = "<font color=\"LightGray\">"; break;
                    case 8: color = "<font color=\"LightGreen\">"; break;
                    case 9: color = "<font color=\"LightSalmon\">"; break;
                    case 10: color = "<font color=\"LightPink\">"; break;
                    default: color = "<font color=\"White\">"; break;
                }
                if (cue.showSpeaker) vtt.add(color+cue.speaker+": "+cue.line1+"\n"); else vtt.add(color+cue.line1+"\n");
                vtt.add(cue.line2+"</font>\n");
                vtt.add("\n");

            }

            FileWriter writer = new FileWriter(outputName);
            for(String line: vtt) {
                writer.write(line);
            }
            writer.close();
            System.out.println("Saved " + outputName + "!");

        } catch(IOException ioe) { return; }
        return;
    }

    public static void saveWebHTML() {
        if (outputNameHTML.isEmpty()) return;
        try {
            ArrayList<String> html = new ArrayList<>();
            StringBuilder lastHtml = new StringBuilder();
            boolean hasPeriod = false;

            int entries = 0;
            for (cueRec cue: cueList) {
                entries++;
                String color;  //https://en.wikipedia.org/wiki/Web_colors
                switch (cue.speakerID) {
                    case 0: color = "<font color=\"Blue\">"; break;
                    case 1: color = "<font color=\"DarkGreen\">"; break;
                    case 2: color = "<font color=\"Purple\">"; break;
                    case 3: color = "<font color=\"Maroon\">"; break;
                    case 4: color = "<font color=\"Red\">"; break;
                    case 5: color = "<font color=\"Teal\">"; break;
                    case 6: color = "<font color=\"DarkCyan\">"; break;
                    case 7: color = "<font color=\"Navy\">"; break;
                    case 8: color = "<font color=\"SlateBlue\">"; break;
                    case 9: color = "<font color=\"Crimson\">"; break;
                    case 10: color = "<font color=\"Brown\">"; break;
                    default: color = "<font color=\"Black\">"; break;
                }

                if (lastHtml.length()>0) {
                    if (!hasPeriod && (isUpperCase(cue.line1.charAt(0)))) lastHtml.append(". "); else
                      if (!cue.showSpeaker) lastHtml.append(" ");
                }

                if (cue.showSpeaker) {
                    if (lastHtml.length() > 0) {
                        lastHtml.append("</font><br/>\n<br/>\n");
                        html.add(lastHtml.toString());
                        lastHtml.setLength(0);
                    }
                    String spkrLink = "";
                    /*if (cue.speaker.startsWith("@")) {  // don't need this, steemit will add it
                        spkrLink = "<a href=\"https://steemit.com/"+cue.speaker+"\">"+cue.speaker+"</a>";
                    } else */spkrLink = "<b>"+cue.speaker+"</b>";
                    String useStrtTm = cue.startTime;
                    if (useStrtTm.startsWith("00:")) useStrtTm = useStrtTm.substring(3);
                    useStrtTm = useStrtTm.substring(0,useStrtTm.indexOf("."));
                    lastHtml.append(spkrLink + " <i>["+useStrtTm+"]</i>: " + color + cue.line1);
                    if (!cue.line2.isEmpty()) lastHtml.append(" " + cue.line2);
                } else {
                    if (cue.isTitle) lastHtml.append("<center><b>");
                    lastHtml.append(cue.line1);
                    if (!cue.line2.isEmpty()) lastHtml.append(" " + cue.line2);
                    if (cue.isTitle) lastHtml.append("</b></center>");
                }
                char lastChar;
                if (!cue.line2.isEmpty()) lastChar = cue.line2.charAt(cue.line2.length()-1); else
                    lastChar = cue.line1.charAt(cue.line1.length()-1);
                if (Character.isLetterOrDigit(lastChar)) hasPeriod = false; else hasPeriod = true;
                if (entries == cueList.size()) {
                    if (!hasPeriod) lastHtml.append(".");
                    if (lastHtml.length() > 0) {
                        lastHtml.append("</font><br/>\n<br/>\n");
                        html.add(lastHtml.toString());
                        lastHtml.setLength(0);
                    }
                }

            }

            if (!html.isEmpty()) {
                FileWriter writer = new FileWriter(outputNameHTML);
                for(String line: html) {
                    writer.write(line);
                }
                writer.close();
                System.out.println("Saved " + outputNameHTML + "!");
            }

        } catch(IOException ioe) { return; }
        return;
    }

    static void getSubRipWildCard(String srtPath) {
        try {
            Iterable<File> fileScan = DirScanner.scan(srtPath);
            Iterator itr = fileScan.iterator();
            while(itr.hasNext()) {
                String srtFile = (String)itr.next().toString();
                srtFile = srtFile.substring(1);  //remove leading "/"
                if (!inputFileNames.contains(srtFile)) inputFileNames.add(srtFile);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public static void main(String[] args) {
	// write your code here

        if (args.length == 0) {
            System.out.println("srt2vtt @host.srt @spkr1.srt @spkr2.srt titles.srt ... output.vtt output.htm");
            System.out.println("srt2vtt @host.srt *.srt ... output.vtt output.htm\n");
            System.out.println("YouTube \"Force Caption\" Tag: \"yt:cc=on\"");
            System.exit(0);
        }

        for (String parm: args) {
            if (parm.contains(".srt")) {
                if (parm.contains("*") || parm.contains("?")) getSubRipWildCard(parm); else
                    if (!inputFileNames.contains(parm)) inputFileNames.add(parm);
            } else
            if (parm.contains(".vtt")) outputName = parm; else
            if (parm.contains(".htm")) outputNameHTML = parm;
        }

        if (outputName.isEmpty()) System.exit(0);

        for (String srtFile : inputFileNames) {
            processSRT(srtFile);
            speakers++;
        }

        Collections.sort(cueList,new cueCompare());
        addAttributes();
        saveWebVTT();
        saveWebHTML();

    }
}
