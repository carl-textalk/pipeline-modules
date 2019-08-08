<?xml version="1.0" encoding="UTF-8"?>
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="1.0"
                xmlns:px="http://www.daisy.org/ns/pipeline/xproc"
                xmlns:d="http://www.daisy.org/ns/pipeline/data"
                type="px:diagram-to-html" name="main">

    <p:documentation xmlns="http://www.w3.org/1999/xhtml">
        <p>Converts any DIAGRAM descriptions in the input fileset into HTML.</p>
    </p:documentation>

    <p:input port="source.fileset"/>
    <p:output port="result.fileset" primary="true">
        <p:documentation xmlns="http://www.w3.org/1999/xhtml">
            <p>A fileset where old DIAGRAM entries have been replaced by entries representing the
            newly produced HTML documents.</p>
            <p>The HTML files have the same location of the DIAGRAM files and have the file
            extension ".xhtml".</p>
        </p:documentation>
        <p:pipe step="convert" port="result"/>
    </p:output>
    <p:output port="result.in-memory" sequence="true">
        <p:documentation xmlns="http://www.w3.org/1999/xhtml">
            <p>Sequence of newly produced HTML documents.</p>
        </p:documentation>
        <p:pipe step="convert" port="secondary"/>
    </p:output>

    <p:for-each name="descriptions">
        <p:output port="result"/>
        <p:iteration-source
            select="/d:fileset/d:file
            [ tokenize(@kind,'\s+') = 'description'
            and @media-type=('application/xml','application/z3998-auth-diagram+xml')]"/>
        <p:load>
            <p:with-option name="href" select="/*/@original-href"/>
        </p:load>
    </p:for-each>

    <p:xslt name="convert" initial-mode="fileset">
        <p:input port="source">
            <p:pipe step="main" port="source.fileset"/>
            <p:pipe step="descriptions" port="result"/>
        </p:input>
        <p:input port="stylesheet">
            <p:document href="../xslt/fileset-convert-diagram.xsl"/>
        </p:input>
        <p:input port="parameters">
            <p:empty/>
        </p:input>
    </p:xslt>

</p:declare-step>
