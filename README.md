# srt2vtt
Merges/Joins multiple SubRIP (.SRT) files into a single WebVTT (.VTT) with Titles and Transcript

The latest release of `srt2vtt` supports many new features:

```
srt2vtt @host.srt @spkr1.srt @spkr2.srt titles.srt ... o:output.vtt output.htm
srt2vtt @host.srt *.srt *.vtt o:output.vtt output.htm {normalize}
   inputs can be SRT or VTT, "normalize" submits each phrase of transcript to AI puncuator
srt2vtt splice edited.vtt m:media.{mp4|ogg|...} ffmpeg=/ffmpeg/bin/
   use captions: "#cut", "#splice", and "#end" to mark end of media
srt2vtt group aligned.vtt
   regroups aligned WebVTT into phrases
srt2vtt align gentle.json
   converts gentle (AI alignment) json format to WebVTT format
   Note: YouTube WebVTT format also contains word-aligned captions for multiple languages
srt2vtt raw {group} transcript.{srt|vtt}
   outputs raw version of transcript (text only) for alignment, etc.
srt2vtt audacity transcript.{srt|vtt}
   outputs audacity-compatible text labels from captions file
YouTube "Force Caption" Tag: "yt:cc=on"  
```

For a more detailed explanation of how to take advantage of this utility, please refer to the following STEEMIT post:
<a href="https://steemit.com/beyondbitcoin/@alexpmorris/automating-multi-lingual-and-multi-speaker-closed-captioning-and-transcripting-workflow-with-srt2vtt">Automating Multi-Lingual and Multi-Speaker Closed-Captioning and Transcripting Workflow with srt2vtt</a>
