/*
 * Copyright 2013 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.java;

import uk.co.real_logic.sbe.generation.CodeGenerator;
import uk.co.real_logic.sbe.generation.OutputManager;
import uk.co.real_logic.sbe.ir.IntermediateRepresentation;
import uk.co.real_logic.sbe.ir.Presence;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;
import uk.co.real_logic.sbe.util.Verify;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static uk.co.real_logic.sbe.generation.java.JavaUtil.javaTypeName;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.toUpperFirstChar;

public class JavaGenerator implements CodeGenerator
{
    /** Class name to be used for visitor pattern that accesses the message header. */
    public static final String MESSAGE_HEADER_VISITOR = "MessageHeader";

    private final IntermediateRepresentation ir;
    private final OutputManager outputManager;

    public JavaGenerator(final IntermediateRepresentation ir, final OutputManager outputManager)
        throws IOException
    {
        Verify.notNull(ir, "ir)");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.outputManager = outputManager;
    }

    public void generateMessageHeaderStub() throws IOException
    {
        try (final Writer out = outputManager.createOutput(MESSAGE_HEADER_VISITOR))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateClassDeclaration(MESSAGE_HEADER_VISITOR, FixedFlyweight.class.getSimpleName()));
            out.append(generateFixedFlyweightCode());

            final List<Token> tokens = ir.header();
            out.append(generatePrimitiveEncodings(tokens.subList(1, tokens.size() - 1)));

            out.append("}\n");
        }
    }

    public void generateTypeStubs() throws IOException
    {
        for (final List<Token> tokens : ir.types())
        {
            switch (tokens.get(0).signal())
            {
                case BEGIN_ENUM:
                    generateEnum(tokens);
                    break;

                case BEGIN_SET:
                    generateChoiceSet(tokens);
                    break;

                case BEGIN_COMPOSITE:
                    generateComposite(tokens);
                    break;
            }
        }
    }

    public void generateMessageStubs() throws IOException
    {
        for (final List<Token> tokens : ir.messages())
        {
            final String className = toUpperFirstChar(tokens.get(0).name());

            try (final Writer out = outputManager.createOutput(className))
            {
                out.append(generateFileHeader(ir.packageName()));
                out.append(generateClassDeclaration(className, MessageFlyweight.class.getSimpleName()));
                out.append(generateMessageFlyweightCode());


                out.append("}\n");
            }
        }
    }

    private void generateChoiceSet(final List<Token> tokens) throws IOException
    {
        final String bitSetName = toUpperFirstChar(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(bitSetName))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateClassDeclaration(bitSetName, FixedFlyweight.class.getSimpleName()));
            out.append(generateFixedFlyweightCode());

            out.append(generateChoices(tokens.subList(1, tokens.size() - 1)));

            out.append("}\n");
        }
    }

    private void generateEnum(final List<Token> tokens) throws IOException
    {
        final String enumName = toUpperFirstChar(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(enumName))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateEnumDeclaration(enumName));

            out.append(generateEnumValues(tokens.subList(1, tokens.size() - 1)));
            out.append(generateEnumBody(tokens.get(0), enumName));

            out.append(generateEnumLookupMethod(tokens.subList(1, tokens.size() - 1), enumName));

            out.append("}\n");
        }
    }

    private void generateComposite(final List<Token> tokens) throws IOException
    {
        final String compositeName = toUpperFirstChar(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(compositeName))
        {
            out.append(generateFileHeader(ir.packageName()));
            out.append(generateClassDeclaration(compositeName, FixedFlyweight.class.getSimpleName()));
            out.append(generateFixedFlyweightCode());

            out.append(generatePrimitiveEncodings(tokens.subList(1, tokens.size() - 1)));

            out.append("}\n");
        }
    }

    private CharSequence generateChoices(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = token.name();
                final String typePrefix = token.encoding().primitiveType().primitiveName();
                final String choiceBitPosition = token.encoding().constVal().toString();

                sb.append(String.format(
                    "\n" +
                    "    public boolean %s()\n" +
                    "    {\n" +
                    "        return CodecUtil.%sGetChoice(buffer, offset, %s);\n" +
                    "    }\n\n" +
                    "    public void %s(final boolean value)\n" +
                    "    {\n" +
                    "        CodecUtil.%sPutChoice(buffer, offset, %s, value);\n" +
                    "    }\n",
                    choiceName,
                    typePrefix,
                    choiceBitPosition,
                    choiceName,
                    typePrefix,
                    choiceBitPosition
                ));
            }
        }

        return sb;
    }

    private CharSequence generateEnumValues(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            final CharSequence constVal = generateLiteral(token);
            sb.append("    ").append(token.name()).append('(').append(constVal).append("),\n");
        }

        sb.setLength(sb.length() - 2);
        sb.append(";\n\n");

        return sb;
    }

    private CharSequence generateEnumBody(final Token token, final String enumName)
    {
        final String javaEncodingType = javaTypeName(token.encoding().primitiveType());

        return String.format(
            "    private final %s value;\n\n"+
            "    %s(final %s value)\n" +
            "    {\n" +
            "        this.value = value;\n" +
            "    }\n\n" +
            "    public %s value()\n" +
            "    {\n" +
            "        return value;\n" +
            "    }\n\n",
            javaEncodingType,
            enumName,
            javaEncodingType,
            javaEncodingType
        );
    }

    private CharSequence generateEnumLookupMethod(final List<Token> tokens, final String enumName)
    {
        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
           "    public static %s lookup(final %s value)\n" +
           "    {\n" +
           "        switch(value)\n" +
           "        {\n",
           enumName,
           javaTypeName(tokens.get(0).encoding().primitiveType())
        ));

        for (final Token token : tokens)
        {
            sb.append(String.format(
                "            case %s: return %s;\n",
                token.encoding().constVal().toString(),
                token.name())
            );
        }

        sb.append(
            "        }\n\n" +
            "        throw new IllegalArgumentException(\"Unknown value: \" + value);\n" +
            "    }\n"
        );

        return sb;
    }

    private CharSequence generateFileHeader(final String packageName)
    {
        return String.format(
            "/* Generated class message */\n" +
            "package %s;\n\n" +
            "import uk.co.real_logic.sbe.generation.java.*;\n\n",
            packageName
        );
    }

    private CharSequence generateClassDeclaration(final String className, final String implementsName)
    {
        return String.format(
            "public class %s implements %s\n" +
            "{\n",
            className,
            implementsName
        );
    }

    private CharSequence generateEnumDeclaration(final String name)
    {
        return "public enum " + name + "\n{\n";
    }

    private CharSequence generatePrimitiveEncodings(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.ENCODING)
            {
                sb.append(generatePrimitiveEncoding(token));
            }
        }

       return sb;
    }

    private CharSequence generatePrimitiveEncoding(final Token token)
    {
        final StringBuilder sb = new StringBuilder();

        if (Presence.CONSTANT == token.encoding().presence())
        {
            sb.append(generateConstEncodingMethod(token));
        }
        else
        {
            sb.append(generatePrimitiveEncodingMethods(token));
        }

        return sb;
    }

    private CharSequence generatePrimitiveEncodingMethods(final Token token)
    {
        final StringBuilder sb = new StringBuilder();
        final String javaTypeName = javaTypeName(token.encoding().primitiveType());
        final String typePrefix = token.encoding().primitiveType().primitiveName();
        final String propertyName = token.name();
        final Integer offset = Integer.valueOf(token.offset());

        final int arrayLength = token.arrayLength();

        if (arrayLength == 1)
        {
            sb.append(String.format(
                "\n" +
                "    public %s %s()\n" +
                "    {\n" +
                "        return CodecUtil.%sGet(buffer, offset + %d);\n" +
                "    }\n\n",
                javaTypeName,
                propertyName,
                typePrefix,
                offset
            ));

            sb.append(String.format(
                "    public void %s(final %s value)\n" +
                "    {\n" +
                "        CodecUtil.%sPut(buffer, offset + %d, value);\n" +
                "    }\n",
                propertyName,
                javaTypeName,
                typePrefix,
                offset
            ));
        }
        else if (arrayLength > 1)
        {
            sb.append(String.format(
                "\n" +
                "    public int %sLength()\n" +
                "    {\n" +
                "        return %d;\n" +
                "    }\n\n",
                propertyName,
                Integer.valueOf(arrayLength)
            ));

            sb.append(String.format(
                "    public %s %s(final int index)\n" +
                "    {\n" +
                "        if (index < 0 || index > %d)\n" +
                "        {\n" +
                "            throw new IllegalArgumentException(\"index out of range: \" + %d);\n" +
                "        }\n\n" +
                "        return CodecUtil.%sGet(buffer, this.offset + %d + (index * %d));\n" +
                "    }\n\n",
                javaTypeName,
                propertyName,
                Integer.valueOf(arrayLength),
                Integer.valueOf(arrayLength),
                typePrefix,
                offset,
                Integer.valueOf(token.encoding().primitiveType().size())
            ));

            sb.append(String.format(
                "    public void %s(final int index, final %s value)\n" +
                "    {\n" +
                "        if (index < 0 || index > %d)\n" +
                "        {\n" +
                "            throw new IllegalArgumentException(\"index out of range: \" + %d);\n" +
                "        }\n\n" +
                "        CodecUtil.%sPut(buffer, this.offset + %d + (index * %d), value);\n" +
                "    }\n",
                propertyName,
                javaTypeName,
                Integer.valueOf(arrayLength),
                Integer.valueOf(arrayLength),
                typePrefix,
                offset,
                Integer.valueOf(token.encoding().primitiveType().size())
            ));
        }

        return sb;
    }

    private CharSequence generateConstEncodingMethod(final Token token)
    {
        final String javaTypeName = javaTypeName(token.encoding().primitiveType());
        final String propertyName = token.name();

        return String.format(
            "\n" +
            "    public %s %s()\n" +
            "    {\n" +
            "        return %s;\n" +
            "    }\n",
            javaTypeName,
            propertyName,
            generateLiteral(token)
        );
    }

    private CharSequence generateFixedFlyweightCode()
    {
        return
            "    private DirectBuffer buffer;\n" +
            "    private int offset;\n\n" +
            "    public void reset(final DirectBuffer buffer, final int offset)\n" +
            "    {\n" +
            "        this.buffer = buffer;\n" +
            "        this.offset = offset;\n" +
            "    }\n";
    }


    private CharSequence generateMessageFlyweightCode()
    {
        return
            "    private DirectBuffer buffer;\n" +
            "    private int offset;\n\n" +
            "    public void resetForEncode(final DirectBuffer buffer, final int offset)\n" +
            "    {\n" +
            "        this.buffer = buffer;\n" +
            "        this.offset = offset;\n" +
            "    }\n\n" +
            "    public void resetForDecode(final DirectBuffer buffer, final int offset)\n" +
            "    {\n" +
            "        this.buffer = buffer;\n" +
            "        this.offset = offset;\n" +
            "    }\n";
    }

    private CharSequence generateLiteral(final Token token)
    {
        String literal = "";

        final String castType = javaTypeName(token.encoding().primitiveType());
        switch (token.encoding().primitiveType())
        {
            case CHAR:
            case UINT8:
            case UINT16:
            case INT8:
            case INT16:
                literal = "(" + castType + ")" + token.encoding().constVal();
                break;

            case UINT32:
            case INT32:
            case FLOAT:
                literal = token.encoding().constVal().toString();
                break;

            case UINT64:
            case INT64:
                literal = token.encoding().constVal() + "L";
                break;

            case DOUBLE:
                literal = token.encoding().constVal() + "d";
        }

        return literal;
    }
}