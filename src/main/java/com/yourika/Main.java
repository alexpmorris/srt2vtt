// srt2vtt, joins/merges multiple SubRip files into a single WebVTT file
// adds additional formatting, including speaker names as extracted from each .srt file
// Also adds titles (via titles.srt) and creates an HTML transcript of the WebVTT output.
//
// by Alexander Paul Morris, 2017-10-04
// steemit, github, twitter: @alexpmorris

package com.yourika;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Character.isUpperCase;

public class Main {

    static String ffmpegPath = "";
    static ArrayList<String> inputFileNames = new ArrayList<String>();
    static int speakers = 0;
    static boolean normalizeMode = false;
    static boolean spliceMode = false;
    static boolean groupMode = false;
    static boolean alignMode = false;
    static boolean rawMode = false;

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
        long startTime_msec;
        long endTime_msec;
        long adj_offset = 0;
        boolean skip = false;
        boolean ytUpdateEndTm = false;
        boolean possibleEOL = false;
    }

    static ArrayList<cueRec> cueList = new ArrayList<>();

    static class cueCompare implements Comparator<cueRec>
    {
        public int compare(cueRec a, cueRec b)
        {
            return a.startTime.compareTo(b.startTime);
        }
    }

    static class spliceRec {
        long startTime_msec;
        long endTime_msec;
        boolean isSplice = false;
    }

    static ArrayList<spliceRec> spliceList = new ArrayList<>();

    static String _lf = System.getProperty("line.separator");

    static Pattern maxLengthWithWords = Pattern.compile("\\G\\s*(.{1,45})(?=\\s|$)", Pattern.DOTALL);

    public static void replaceAll(StringBuilder builder, String from, String to)
    {
        int index = builder.indexOf(from);
        while (index != -1)
        {
            builder.replace(index, index + from.length(), to);
            index += to.length(); // Move to the end of the replacement
            index = builder.indexOf(from, index);
        }
    }

    //convert https://lowerquality.com/gentle/ json -> srt  (for word-level alignment)
    //probably best to use "conservative" setting with gentle
    public static void alignGentleJson2SRT(String fn) {
        StringBuilder str_json = new StringBuilder();
        try (Scanner scanner = new Scanner(Paths.get(fn))) {
            cueRec cue = null;
            while (scanner.hasNextLine()) {
                str_json.append(scanner.nextLine());
            }
            JSONObject json = new JSONObject(str_json.toString());
            String transcript = json.get("transcript").toString().replace("\n"," ").replace("\r"," ");
            String[] script_arr = transcript.split(" ");
            int script_arr_pos = 0;
            JSONArray word_arr = (JSONArray) json.get("words");
            for (int i = 0; i < word_arr.length(); i++) {
                String str_word = word_arr.get(i).toString().toLowerCase();
                JSONObject word_json = new JSONObject(str_word);
                if (word_json.has("start")) {
                    cue = new cueRec();
                    cue.startTime_msec = Math.round(Double.parseDouble(word_json.get("start").toString()) * 1000);
                    cue.endTime_msec = Math.round(Double.parseDouble(word_json.get("end").toString()) * 1000);
                    cue.startTime = msec2vtt(cue.startTime_msec);
                    cue.endTime = msec2vtt(cue.endTime_msec);

                    //map original transcript word to aligned word (to preserve punctuation, capitals, etc)
                    String word = word_json.get("word").toString();
                    while (!script_arr[script_arr_pos].toLowerCase().contains(word)) script_arr_pos++;
                    cue.line1 = script_arr[script_arr_pos];

                    cueList.add(cue);
                }
            }
            System.out.println("Parsed "+fn+"!");
            String[] fn_split = fn.split("[.]");
            saveWebVTT(cueList,fn_split[0]+".vtt", true);

        } catch (IOException e) {
            System.out.println(" [" + e.getMessage()+"]");
        }
    }

    //this feature can be used to submit text for "normalizing" via a web service to correct
    //punctuation, grammar, etc in caption blocks.
    //use at your discretion, as it may or may not produce better results...
    public static StringBuilder normalizeBlockPuncuation(String speaker, StringBuilder block) {
        try {
            System.out.print("normalizingBlock for "+speaker+" [l:"+block.length()+"]...");
            long dtNow = new Date().getTime();
            URL object = new URL("http://bark.phon.ioc.ee/punctuator");
            HttpURLConnection con = (HttpURLConnection) object.openConnection();
            con.setDoOutput(true);
            con.setDoInput(true);
            con.setConnectTimeout(2500);
            con.setReadTimeout(90000);
            con.setRequestProperty("User-Agent", "Mozilla 5.0");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            con.setRequestProperty("Accept", "*/*");
            con.setRequestMethod("POST");

            OutputStreamWriter wr = new OutputStreamWriter(con.getOutputStream());
            wr.write("text="+block.toString());
            wr.flush();

            //display what returns the POST request
            StringBuilder sb = new StringBuilder();
            int HttpResult = con.getResponseCode();
            if (HttpResult == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getInputStream(), "utf-8"));
                String line = null;
                while ((line = br.readLine()) != null) sb.append(line);
                System.out.println("done! [" + (new Date().getTime() - dtNow)/1000 + "s]");
                //correct a few AI anomalies...
                replaceAll(sb,".. ", ". ");
                replaceAll(sb,"., ", ". ");
                replaceAll(sb,"'Ll", "'ll");
                replaceAll(sb,"'S", "'s");
                replaceAll(sb," gon na ", " gonna ");
                replaceAll(sb, "[,", "[");
                replaceAll(sb, ", ]", "]");
                System.out.println("pre:\""+block.toString()+"\"");
                System.out.println("post:\""+sb.toString()+"\"");
                if (sb.length() == 0) return block; else return sb;
            } else {
                System.out.println(" [" + con.getResponseMessage()+"]");
            }
        } catch (IOException e) {
            System.out.println(" [" + e.getMessage()+"]");
        }
        return block;
    }

    //youtube provides word-level caption alignment on WebVTT export
    public static cueRec parseYouTubeVttCaption(cueRec cue) {
        //String  test ="one<00:00:01.879><c> from</c><00:00:02.879><c> fairest</c><00:00:03.389><c> creatures</c><00:00:03.899><c> we</c><00:00:04.230><c> desire</c>";
        String endTime = cue.endTime;
        cueRec prvCue = null;
        String[] arr = cue.line1.split("[<]+");
        for (int i = 0; i<arr.length; i++) {
            String elem = arr[i];
            if (!elem.contains(">")) cue.line1 = elem; else {
                if (elem.substring(2,3).equals(":") &&
                    elem.substring(5,6).equals(":")) {
                    cue.endTime = elem.substring(0,elem.length()-1);
                    cueList.add(cue);
                    prvCue = cue;
                    cue = new cueRec();
                    cue.speaker = prvCue.speaker;
                    cue.speakerID = prvCue.speakerID;
                    cue.isHost = prvCue.isHost;
                    cue.startTime = prvCue.endTime;
                    cue.endTime = endTime;
                } else
                if (elem.startsWith("c")) {
                    String str_cap = elem.substring(elem.indexOf(">")+1).trim();
                    if (!str_cap.isEmpty()) cue.line1 = str_cap;
                }
            }
        }
        cue.ytUpdateEndTm = true;
        return cue;
    }

    public static void processSRT(String fn) {
        String[] fn_arr;
        if (fn.contains("\\")) fn_arr = fn.split("[\\\\.]"); else //extract speaker name from filename
            fn_arr = fn.split("[//.]");
        String speaker = fn_arr[fn_arr.length-2];
        boolean isHost = cueList.size() == 0;
        boolean isTitles = !isHost && speaker.equals("titles");
        boolean isYoutTubeVTT = false;
        String nextStartTm = "";
        String nextEndTm = "";
        try (Scanner scanner = new Scanner(Paths.get(fn))) {
            cueRec cue = null;
            while (scanner.hasNextLine()) {
                if (cue == null) cue = new cueRec();
                cue.speaker = speaker;
                cue.isHost = isHost;
                cue.isTitle = isTitles;
                if (!isTitles) cue.speakerID = speakers; else cue.speakerID = 99;
                if (!nextStartTm.isEmpty()) {
                    cue.startTime = nextStartTm;;
                    cue.endTime = nextEndTm;
                    nextStartTm = "";
                    nextEndTm = "";
                }
                String line = scanner.nextLine();
                if (line.equals("##")) isYoutTubeVTT = true; else
                if (line.contains(" --> ")) {
                    line = line.replace(",",".").trim();
                    String[] times = line.split(" ");
                    if (cue.ytUpdateEndTm && !cue.line1.isEmpty()) {
                        cue.endTime = times[0];
                        cue.ytUpdateEndTm = false;
                        cueList.add(cue);
                        cue = null;
                    }
                    nextStartTm = times[0];
                    nextEndTm = times[2];
                } else if (line.isEmpty()) {
                    if (!cue.endTime.isEmpty() && !cue.ytUpdateEndTm) {
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
                        if (cue.line1.isEmpty()) {
                            cue.line1 = line.trim();
                            if (isYoutTubeVTT && cue.line1.contains("</c>")) cue = parseYouTubeVttCaption(cue);
                        } else cue.line2 = line.trim();
                    }
                }
            }

            if ((cue != null) && cue.ytUpdateEndTm && !cue.line1.isEmpty()) {
                cue.ytUpdateEndTm = false;
                cueList.add(cue);
                cue = null;
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

    public static void saveWebVTT(ArrayList<cueRec> cueList, String outputName, boolean saveRawCaption) {
        try {
            ArrayList<String> vtt = new ArrayList<>();

            if (!rawMode) vtt.add("WEBVTT"+_lf+_lf);

            String startTime = "";
            String startAttr = "";
            String line = "";

            int entries = 0;
            for (int i = 0; i < cueList.size(); i++) {
                cueRec cue = cueList.get(i);
                if (groupMode && !cue.skip && cue.line2.isEmpty()) {
                   if (startTime.isEmpty()) startTime = cue.startTime;
                   if (startAttr.isEmpty()) startAttr = cue.attr;
                   line += " "+cue.line1;
                   boolean hasTerminator = (line.length() >= 25) && (cue.line1.endsWith(".") || cue.line1.endsWith(":") || cue.line1.endsWith(";") || cue.line1.endsWith(","));
                   if ((line.length() > 90) || cue.possibleEOL || hasTerminator || (i==cueList.size()-1) ) {
                       cue.startTime = startTime;
                       if (cue.attr.isEmpty()) cue.attr = startAttr;

                       Matcher match = maxLengthWithWords.matcher(line);
                       if (match.find()) cue.line1 = match.group(0).trim();
                       if (match.find()) cue.line2 = (match.group(0) + " " + cue.line2).trim();

                       startTime = "";
                       line = "";
                       startAttr = "";

                    } else cue.skip = true;

                }
                if (!cue.skip) {
                    entries++;
                    if (!rawMode) {
                        vtt.add(Integer.toString(entries) + _lf);
                        vtt.add(cue.startTime + " --> " + cue.endTime + " " + cue.attr + _lf);
                    }
                    if (spliceMode || saveRawCaption || rawMode) {
                        vtt.add(cue.line1+_lf);
                        if (!cue.line2.isEmpty()) vtt.add(cue.line2+_lf);
                    } else {
                        /*String color;  //https://en.wikipedia.org/wiki/Web_colors
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
                        if (cue.showSpeaker) vtt.add(color+cue.speaker+": "+cue.line1+_lf); else vtt.add(color+cue.line1+_lf);
                        vtt.add(cue.line2+"</font>_lf);*/
                        if (cue.showSpeaker) vtt.add(cue.speaker+": "+cue.line1+_lf); else vtt.add(cue.line1+_lf);
                        if (!cue.line2.isEmpty()) vtt.add(cue.line2+_lf);
                    }
                    if (!rawMode) vtt.add(_lf);
                }
            }

            FileWriter writer = new FileWriter(outputName);
            for(String tmp_line : vtt) {
                writer.write(tmp_line);
            }
            writer.close();
            System.out.println("Saved " + outputName + "!");

        } catch(IOException ioe) { return; }
        return;
    }

    public static void saveWebHTML(String outputNameHTML) {
        if (outputNameHTML.isEmpty()) return;
        try {
            ArrayList<String> html = new ArrayList<>();
            StringBuilder lastHtml = new StringBuilder();
            StringBuilder lastBlock = new StringBuilder();
            boolean hasPeriod = false;
            String currentSpeaker = "";

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

                if (lastBlock.length()>0) {
                    if (!hasPeriod && (isUpperCase(cue.line1.charAt(0)))) lastBlock.append(". "); else
                      if (!cue.showSpeaker) lastBlock.append(" ");
                }

                if (cue.showSpeaker) {
                    if (lastBlock.length() > 0) {
                        if (normalizeMode) lastBlock = normalizeBlockPuncuation(currentSpeaker, lastBlock);
                        lastHtml.append(lastBlock.toString());
                        lastHtml.append("</font><br/>"+_lf+"<br/>"+_lf);
                        html.add(lastHtml.toString());
                        lastHtml.setLength(0);
                        lastBlock.setLength(0);
                    }
                    String spkrLink = "";
                    /*if (cue.speaker.startsWith("@")) {  // don't need this, steemit will add it
                        spkrLink = "<a href=\"https://steemit.com/"+cue.speaker+"\">"+cue.speaker+"</a>";
                    } else */spkrLink = "<b>"+cue.speaker+"</b>";
                    String useStrtTm = cue.startTime;
                    if (useStrtTm.startsWith("00:")) useStrtTm = useStrtTm.substring(3);
                    useStrtTm = useStrtTm.substring(0,useStrtTm.indexOf("."));
                    lastHtml.append(spkrLink + " <i>["+useStrtTm+"]</i>: " + color);
                    lastBlock.append(cue.line1);
                    if (!cue.line2.isEmpty()) lastBlock.append(" " + cue.line2);
                    currentSpeaker = cue.speaker;
                } else {
                    if (cue.isTitle) lastHtml.append("<center><b>"+cue.line1+" "+cue.line2+"</b></center>"); else {
                        lastBlock.append(cue.line1);
                        if (!cue.line2.isEmpty()) lastBlock.append(" " + cue.line2);
                    }
                }
                char lastChar;
                if (!cue.line2.isEmpty()) lastChar = cue.line2.charAt(cue.line2.length()-1); else
                    lastChar = cue.line1.charAt(cue.line1.length()-1);
                if (Character.isLetterOrDigit(lastChar)) hasPeriod = false; else hasPeriod = true;
                if (entries == cueList.size()) {
                    if (!hasPeriod) lastBlock.append(".");
                    if (lastBlock.length() > 0) {
                        if (normalizeMode) lastBlock = normalizeBlockPuncuation(currentSpeaker, lastBlock);
                        lastHtml.append(lastBlock.toString());
                        lastHtml.append("</font><br/>"+_lf+"<br/>"+_lf);
                        html.add(lastHtml.toString());
                        lastHtml.setLength(0);
                        lastBlock.setLength(0);
                    }
                }

            }

            if (!html.isEmpty()) {
                FileWriter writer = new FileWriter(outputNameHTML);
                for(String line : html) {
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
            DirScanner.recurseSubDirs = false;
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

    String addZeros(int number){
        return number <= 9 ? "0" + number : number+"";
    }

    static long vtt2msec(String time) {
        long h, m, s, ms;
        time = time.replace(".",":");
        String[] hms = time.split(":");
        h = Integer.parseInt(hms[0]);
        m = Integer.parseInt(hms[1]);
        s = Integer.parseInt(hms[2]);
        ms = Integer.parseInt(hms[3]);
        return ms + (s*1000) + (m*60000) + (h*3600000);
    }

    static String msec2vtt(long msec) {
        long h, m, s, ms;
        h = msec / 3600000;
            msec -= h*3600000;
        m = msec / 60000;
            msec -= m*60000;
        s = msec / 1000;
            msec -= s*1000;
        return String.format("%02d",h)+":"+String.format("%02d",m)+":"+String.format("%02d",s)+"."+String.format("%03d",msec);
    }

    public static void processVTT(String fn) {
        long lastCut = 0;
        long lastSplice = 0;
        long adjOffset = 0;
        try (Scanner scanner = new Scanner(Paths.get(fn))) {
            cueRec cue = null;
            cueRec prvCue = null;
            while (scanner.hasNextLine()) {
                if (cue == null) cue = new cueRec();
                String line = scanner.nextLine();
                if (line.contains(" --> ")) {
                    line = line.replace(",",".").trim();
                    String[] times = line.split(" ");
                    cue.startTime = times[0];
                    cue.endTime = times[2];
                    cue.startTime_msec = vtt2msec(cue.startTime);
                    cue.endTime_msec = vtt2msec(cue.endTime);
                    for (int i = 3; i <= times.length-1; i++) cue.attr += times[i] + " ";
                    cue.attr = cue.attr.trim();
                    cue.adj_offset = adjOffset;
                } else if (line.isEmpty()) {
                    if (!cue.endTime.isEmpty()) {
                        if (spliceMode) {
                            if (cue.line1.isEmpty() || cue.line1.toLowerCase().startsWith("#cut")) {
                                if ((prvCue != null) && cue.line1.toLowerCase().startsWith("#cut<")) {
                                    prvCue.line2 += cue.line1.substring(4);
                                }
                                spliceRec splice = new spliceRec();
                                splice.isSplice = false;
                                splice.startTime_msec = lastCut;
                                splice.endTime_msec = cue.startTime_msec - lastCut;
                                lastCut = cue.endTime_msec;
                                spliceList.add(splice);
                                adjOffset += cue.endTime_msec - cue.startTime_msec;
                                cue.skip = true;
                            } else if (cue.line1.toLowerCase().startsWith("#splice")) {
                                if ((prvCue != null) && cue.line1.toLowerCase().startsWith("#splice<")) {
                                    prvCue.line2 += cue.line1.substring(7);
                                }
                                spliceRec splice = new spliceRec();
                                splice.isSplice = true;
                                splice.startTime_msec = lastSplice - adjOffset;
                                if (splice.startTime_msec < 0) splice.startTime_msec = 0;
                                splice.endTime_msec = cue.startTime_msec - lastSplice - adjOffset;
                                lastSplice = cue.startTime_msec;
                                spliceList.add(splice);
                                cue.skip = true;
                            } else if (cue.line1.toLowerCase().equals("#end")) {
                                if ((prvCue != null) && cue.line1.toLowerCase().startsWith("#end<")) {
                                    prvCue.line2 += cue.line1.substring(4);
                                }
                                cue.skip = true;
                            }
                        }
                       if (groupMode && (prvCue != null) && ((cue.line1.startsWith("@") && cue.line1.contains(":")) || (cue.endTime_msec-cue.startTime_msec >= 900))) prvCue.possibleEOL = true;
                       cueList.add(cue);
                       prvCue = cue;
                       cue = null;
                    }
                } else {
                    if ((cue != null) && (!cue.endTime.isEmpty())) {
                        if (cue.line1.isEmpty()) cue.line1 = line.trim();
                        else cue.line2 = line.trim();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        if (lastCut > 0) {
            cueRec cue = cueList.get(cueList.size()-1);
            spliceRec splice = new spliceRec();
            splice.isSplice = false;
            splice.startTime_msec = lastCut;
            splice.endTime_msec = cue.endTime_msec;
            spliceList.add(splice);
            cue.skip = true;
        }
        if (lastSplice > 0) {
            cueRec cue = cueList.get(cueList.size()-1);
            spliceRec splice = new spliceRec();
            splice.isSplice = true;
            splice.startTime_msec = lastSplice;
            splice.endTime_msec = cue.endTime_msec;
            spliceList.add(splice);
            cue.skip = true;
        }
        System.out.println("Parsed "+fn+"!");
    }

    public static void processSplices(String vttName, String inputNameMP4) {
        String[] fname = inputNameMP4.split("[.]");
        String vidName = fname[0];
        String vidExt = "."+fname[1];
        fname = vttName.split("[.]");
        String vttFileName = fname[0];
        long vidId = 0;
        boolean hasCuts = false;
        String useVideoFile = vidName + vidExt;
        ArrayList<String> actions = new ArrayList<>();

        String OS = System.getProperty("os.name").toLowerCase();
        boolean isWin = OS.indexOf("win") >= 0;

        for (int i=0; i<=spliceList.size()-1; i++) {
            spliceRec splice = spliceList.get(i);
            if (!splice.isSplice) {
                actions.add(ffmpegPath+"ffmpeg -y -i "+useVideoFile+" -ss "+msec2vtt(splice.startTime_msec)+
                            " -t "+msec2vtt(splice.endTime_msec)+" "+vidName+"_"+Long.toString(vidId)+vidExt);
                vidId++;
                hasCuts = true;
            }
        }
        if (hasCuts) {
            for (int i = cueList.size() - 1; i >= 0; i--) {
                cueRec cue = cueList.get(i);
                if (cue.line1.isEmpty() || cue.line1.toLowerCase().equals("cut")) cueList.remove(i);
                else {
                    cue.startTime_msec -= cue.adj_offset;
                    cue.endTime_msec -= cue.adj_offset;
                    cue.startTime = msec2vtt(cue.startTime_msec);
                    cue.endTime = msec2vtt(cue.endTime_msec);
                }
            }
            String concat = "";
            for (int i = 0; i < vidId; i++) concat += vidName + "_" + Long.toString(i) + vidExt + "|";
            actions.add(ffmpegPath + "ffmpeg -y -i \"concat:" + concat.substring(0, concat.length() - 1) + "\" -c copy " + vidName + "_cut" + vidExt);
            String useDel = "rm";  if (isWin) useDel = "del";
            for (int i = 0; i < vidId; i++) actions.add(useDel+" " + vidName + "_" + Long.toString(i) + vidExt);
            useVideoFile = vidName + "_cut" + vidExt;
            saveWebVTT(cueList,vttFileName+"_cut.vtt", false);
        }

        vidId = 0;
        for (int i=0; i<=spliceList.size()-1; i++) {
            spliceRec splice = spliceList.get(i);
            if (splice.isSplice) {
                actions.add(ffmpegPath+"ffmpeg -y -i "+useVideoFile+" -ss "+msec2vtt(splice.startTime_msec)+
                        " -t "+msec2vtt(splice.endTime_msec)+" "+vidName+"_splice_"+Long.toString(vidId)+vidExt);
                splice.endTime_msec += splice.startTime_msec;
                ArrayList<cueRec> tmpList = new ArrayList<>(cueList);
                for (int j = tmpList.size() - 1; j >= 0; j--) {
                    cueRec cue = tmpList.get(j);
                    if ((cue.startTime_msec < splice.startTime_msec) || (cue.endTime_msec > splice.endTime_msec)) tmpList.remove(j); else {
                        cue.startTime_msec -= splice.startTime_msec;
                        cue.endTime_msec -= splice.startTime_msec;
                        cue.startTime = msec2vtt(cue.startTime_msec);
                        cue.endTime = msec2vtt(cue.endTime_msec);
                    }
                }
                saveWebVTT(tmpList,vttFileName+"_splice_"+Long.toString(vidId)+".vtt", true);
                vidId++;
            }
        }

        if (actions.size() > 0) {
            try {
                String batchFile = vidName+".sh";  if (isWin) batchFile = vidName+".bat";
                FileWriter writer = new FileWriter(batchFile);
                for (String line : actions) { writer.write(line+_lf); }
                writer.close();
                System.out.println("Run \""+batchFile+"\" to Complete Video/Audio Operations!");
            } catch (Exception e) { e.printStackTrace(); }
        }

    }

    public static void processGroupMode(String vttName) {
        String[] fname = vttName.split("[.]");
        String vttFileName = fname[0];
        saveWebVTT(cueList,vttFileName+"_grouped.vtt", true);
    }

    public static void processRawMode(String vttName) {
        String[] fname = vttName.split("[.]");
        String vttFileName = fname[0];
        saveWebVTT(cueList,vttFileName+".txt", true);
    }

    public static void main(String[] args) {
	// write your code here

        String outputName = "";
        String outputNameHTML = "";
        String inputNameMP4 = "";

        //alignGentleJson2SRT("/python27/aeneas/align.json");
        //processSRT("/python27/aeneas/captions.vtt");
        //saveWebVTT(cueList, "/python27/aeneas/captions-parsed.vtt", true);
        //alignSRT("");
        //System.exit(0);

        if (args.length == 0) {
            System.out.println("srt2vtt @host.srt @spkr1.srt @spkr2.srt titles.srt ... o:output.vtt output.htm");
            System.out.println("srt2vtt @host.srt *.srt *.vtt o:output.vtt output.htm {normalize}");
            System.out.println("   inputs can be SRT or VTT, \"normalize\" submits each phrase of transcript to AI puncuator");
            System.out.println("srt2vtt splice edited.vtt m:media.{mp4|ogg|...} ffmpeg=/ffmpeg/bin/");
            System.out.println("   use captions: \"#cut\", \"#splice\", and \"#end\" to mark end of media");
            System.out.println("srt2vtt group aligned.vtt");
            System.out.println("   regroups aligned WebVTT into phrases");
            System.out.println("srt2vtt align gentle.json");
            System.out.println("   converts gentle (AI alignment) json format to WebVTT format");
            System.out.println("   Note: YouTube WebVTT format also contains word-aligned captions for multiple languages");
            System.out.println("srt2vtt raw {group} transcript.{srt|vtt}");
            System.out.println("   outputs raw version of transcript (text only) for alignment, etc.");
            System.out.println("YouTube \"Force Caption\" Tag: \"yt:cc=on\"");
            System.exit(0);
        }

        for (String parm: args) {
            if (!parm.contains(":") && ((parm.contains(".srt") || parm.contains(".vtt")))) {
                if (parm.contains("*") || parm.contains("?")) getSubRipWildCard(parm); else
                    if (!inputFileNames.contains(parm)) inputFileNames.add(parm);
            } else
            if (parm.startsWith("o:") && ((parm.contains(".srt") || parm.contains(".vtt")))) outputName = parm.substring(2,parm.length()); else
            if (parm.contains(".htm")) outputNameHTML = parm; else
            if (parm.equals("normalize")) normalizeMode = true; else
            if (parm.equals("group")) groupMode = true; else
            if (parm.equals("align")) alignMode = true; else
            if (alignMode & parm.contains(".json")) outputName = parm; else
            if (parm.equals("raw")) rawMode = true; else
            if (parm.equals("splice")) spliceMode = true; else
            if (spliceMode && (parm.startsWith("m:") && parm.contains("."))) inputNameMP4 = parm.substring(2,parm.length()); else
            if (spliceMode && parm.contains("ffmpeg=")) ffmpegPath = parm.split("[=]")[1];
        }

        //get outputName from inputFileNames for cut/splice mode
        if ((spliceMode || groupMode || rawMode) && (inputFileNames.size() > 0)) outputName = inputFileNames.get(0);

        if (outputName.isEmpty()) System.exit(0);

        if (rawMode) {
            processVTT(outputName);
            processRawMode(outputName);
        } else
        if (alignMode) {
            alignGentleJson2SRT(outputName);
        } else
        if (groupMode) {
            processVTT(outputName);
            processGroupMode(outputName);
        } else
        if (spliceMode) {
            groupMode = true;
            if (!inputNameMP4.isEmpty()) {
                processVTT(outputName);
                processSplices(outputName, inputNameMP4);
            }
        } else {

            for (String srtFile : inputFileNames) {
                processSRT(srtFile);
                speakers++;
            }

            Collections.sort(cueList, new cueCompare());
            addAttributes();
            saveWebVTT(cueList, outputName, false);
            saveWebHTML(outputNameHTML);

        }

    }
}

//sample command lines:
//srt2vtt @officialfuzzy.srt *.srt titles.srt o:wt-20170930.vtt wt-20170930.htm normalize
//srt2vtt splice output.vtt m:output.ogg ffmpeg=\genutils\ffmpeg\bin\
//srt2vtt group aligned.vtt