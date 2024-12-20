/*
 * Copyright (c) 2013-2021 Chris Newland.
 * Licensed under https://github.com/AdoptOpenJDK/jitwatch/blob/master/LICENSE-BSD
 * Instructions: https://github.com/AdoptOpenJDK/jitwatch/wiki
 */
package org.adoptopenjdk.jitwatch.model;

import org.adoptopenjdk.jitwatch.logger.Logger;
import org.adoptopenjdk.jitwatch.logger.LoggerFactory;
import org.adoptopenjdk.jitwatch.model.bytecode.ClassBC;
import org.adoptopenjdk.jitwatch.util.ParseUtil;
import org.adoptopenjdk.jitwatch.util.StringUtil;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.*;

public class MemberSignatureParts
{
	private String fullyQualifiedClassName;
	private int modifier;
	private List<String> modifierList;
	private String returnType;
	private String memberName;
	private List<String> paramTypeList;

	private static final Pattern PATTERN_ASSEMBLY_SIGNATURE = Pattern.compile("^(.*)\\s'(.*)'\\s'(\\(.*\\))(.*)'\\sin\\s'(.*)'");

	private static final Logger logger = LoggerFactory.getLogger(MemberSignatureParts.class);

	// LinkedHashMap to ensure entry set iteration matches insertion order
	private static final Map<String, Integer> modifierMap = new LinkedHashMap<>();

	static
	{
		addModifierMapping(Modifier.PUBLIC);
		addModifierMapping(Modifier.PROTECTED);
		addModifierMapping(Modifier.PRIVATE);
		addModifierMapping(Modifier.ABSTRACT);
		addModifierMapping(Modifier.STATIC);
		addModifierMapping(Modifier.FINAL);
		addModifierMapping(Modifier.SYNCHRONIZED);
		addModifierMapping(Modifier.NATIVE);
		addModifierMapping(Modifier.STRICT);
	}

	private static void addModifierMapping(int modifier)
	{
		modifierMap.put(Modifier.toString(modifier), modifier);
	}

	private MemberSignatureParts()
	{
		modifierList = new ArrayList<>();
		paramTypeList = new ArrayList<>();
		modifier = 0;
	}

	private static void completeSignature(String origSig, MemberSignatureParts msp)
	{		
		if (msp.memberName != null)
		{
			// Constructors will return void for returnType
			if (S_CONSTRUCTOR_INIT.equals(msp.memberName) || msp.memberName.equals(msp.fullyQualifiedClassName))
			{
				msp.memberName = StringUtil.getUnqualifiedClassName(msp.fullyQualifiedClassName);
				msp.returnType = Void.TYPE.getName();
			}
		}
		else
		{
			if (DEBUG_LOGGING)
			{
				logger.debug("MemberSignatureParts.memberName was null for signature: '{}'\n{}", origSig, msp);
			}
		}

		Iterator<String> paramIter = msp.paramTypeList.iterator();

		while (paramIter.hasNext())
		{
			String paramType = paramIter.next();

			if (ParseUtil.looksLikeSyntheticBridgeConstructorParam(paramType))
			{
				logger.debug("Synthetic bridge constructor arg: {}", paramType);
				paramIter.remove();
			}
		}
	}

	public static MemberSignatureParts fromParts(String fullyQualifiedClassName, String memberName, String returnType,
			List<String> paramTypes)
	{
		MemberSignatureParts msp = new MemberSignatureParts();

		msp.fullyQualifiedClassName = fullyQualifiedClassName;

		msp.memberName = memberName;

		msp.paramTypeList.addAll(paramTypes);

		msp.returnType = returnType;

		completeSignature(fullyQualifiedClassName + S_COMMA + memberName + S_COMMA + returnType, msp);

		return msp;
	}

	public static MemberSignatureParts fromLogCompilationSignature(String toParse) throws LogParseException
	{
		MemberSignatureParts msp = new MemberSignatureParts();

		String[] parts = ParseUtil.splitLogSignatureWithRegex(toParse);

		msp.fullyQualifiedClassName = parts[0].replaceAll(S_SLASH, S_DOT);
		msp.memberName = parts[1];

		String paramTypes = parts[2];
		String returnType = parts[3];

		setParamsAndReturn(msp, paramTypes, returnType);

		completeSignature(toParse, msp);

		return msp;
	}

	private static boolean isStaticInitialiser(String bytecodeSignature)
	{
		boolean isClinit = false;

		if (bytecodeSignature != null && bytecodeSignature.startsWith(S_BYTECODE_STATIC_INITIALISER_SIGNATURE))
		{
			isClinit = true;
		}

		return isClinit;
	}

	// TODO unit test me!
	public static MemberSignatureParts fromBytecodeComment(String toParse) throws LogParseException
	{
		String logCompilationSignature = ParseUtil.bytecodeCommentSignatureToLogCompilationSignature(toParse);

		return fromLogCompilationSignature(logCompilationSignature);
	}

	public static String isolateGenericsTag(String input)
	{
		StringBuilder builder = new StringBuilder();

		int openAngleBrackets = 0;

		boolean replaced = false;

		int length = input.length();

		for (int i = 0; i < length; i++)
		{
			char c = input.charAt(i);

			if (c == C_OPEN_ANGLE)
			{
				if (openAngleBrackets == 0 && !replaced)
				{
					builder.append(C_OPEN_BRACE);
				}
				else
				{
					builder.append(c);
				}

				openAngleBrackets++;
			}
			else if (c == C_CLOSE_ANGLE)
			{
				openAngleBrackets--;

				if (openAngleBrackets == 0 && !replaced)
				{
					builder.append(C_CLOSE_BRACE);
					replaced = true;
				}
				else
				{
					builder.append(c);
				}
			}
			else
			{
				builder.append(c);
			}
		}

		return builder.toString();
	}

	public static boolean signatureHasGenerics(String input)
	{
		return input.contains(" extends ") || input.contains(" super ");
	}

	public static MemberSignatureParts fromBytecodeSignature(String fqClassName, String toParse, ClassBC classBC)
	{
		if (signatureHasGenerics(toParse))
		{
			toParse = isolateGenericsTag(toParse);
		}

		MemberSignatureParts msp = new MemberSignatureParts();

		msp.fullyQualifiedClassName = fqClassName;

		if (isStaticInitialiser(toParse))
		{
			msp.memberName = S_STATIC_INIT;
			msp.returnType = Void.TYPE.getName();

			return msp;
		}

		StringBuilder builder = new StringBuilder();

		builder.append("^[ ]*");

		for (String mod : modifierMap.keySet())
		{
			builder.append(S_OPEN_PARENTHESES).append(mod).append(S_SPACE).append(S_CLOSE_PARENTHESES).append(C_QUESTION);
		}

		String regexGenerics = "(\\{.*\\} )?";
		String regexReturnType = "(.* )?"; // optional could be constructor
		String regexMethodName = ParseUtil.METHOD_NAME_REGEX_GROUP;
		String regexParams = "(\\(.*\\))";
		String regexRest = "(.*)";

		builder.append(regexGenerics);
		builder.append(regexReturnType);
		builder.append(regexMethodName);
		builder.append(regexParams);
		builder.append(regexRest);

		// logger.info("\n{}\n{}", toParse, builder);

		final Pattern patternBytecodeSignature = Pattern.compile(builder.toString());

		Matcher matcher = patternBytecodeSignature.matcher(toParse);

		int modifierCount = modifierMap.size();

		Map<String, String> methodGenericsMap = null;

		if (matcher.find())
		{
			int count = matcher.groupCount();

			for (int i = 1; i < count; i++)
			{
				String group = matcher.group(i);

				if (group != null)
				{
					group = group.trim();
				}

				if (group != null && i <= modifierCount)
				{
					msp.modifierList.add(group);

					// add bitset value for this modifier
					msp.modifier += modifierMap.get(group);
				}

				if (i == modifierCount + 1)
				{
					if (group != null)
					{
						methodGenericsMap = msp.buildGenerics(group);
					}
				}

				if (i == modifierCount + 2)
				{
					if (group != null)
					{
						msp.returnType = msp.replaceGenerics(group, classBC, methodGenericsMap);
					}
				}

				if (i == modifierCount + 3)
				{
					if (group != null)
					{
						msp.memberName = group;
					}
				}

				if (i == modifierCount + 4)
				{
					if (group != null)
					{
						msp.buildParamTypes(group, classBC, methodGenericsMap);
					}
				}
			}
		}

		completeSignature(toParse, msp);

		return msp;
	}

	public static MemberSignatureParts fromAssembly(final String toParse) throws LogParseException
	{
		MemberSignatureParts msp = new MemberSignatureParts();

		String line = toParse.replace(S_ENTITY_APOS, S_QUOTE);

		Matcher matcher = PATTERN_ASSEMBLY_SIGNATURE.matcher(line);

		if (matcher.find())
		{
			if (DEBUG_LOGGING_ASSEMBLY)
			{
				for (int i = 0; i < matcher.groupCount(); i++)
				{
					logger.debug("part[{}] = '{}'", i, matcher.group(i));
				}
			}

			String memberName = matcher.group(2);
			String paramTypes = matcher.group(3).replace(S_OPEN_PARENTHESES, S_EMPTY).replace(S_CLOSE_PARENTHESES, S_EMPTY);
			String returnType = matcher.group(4);
			String className = matcher.group(5).replace(S_SLASH, S_DOT);

			msp.memberName = memberName;
			msp.fullyQualifiedClassName = className;

			setParamsAndReturn(msp, paramTypes, returnType);

		}

		completeSignature(toParse, msp);

		return msp;
	}

	private static void setParamsAndReturn(MemberSignatureParts msp, String paramTypes, String returnType) throws LogParseException
	{
		String[] paramClassesName = ParseUtil.getClassNames(paramTypes);
		String[] returnClassesNames = ParseUtil.getClassNames(returnType);

		String returnClassName;

		if (returnClassesNames.length == 1)
		{
			returnClassName = ParseUtil.expandParameterType(returnClassesNames[0]);
		}
		else
		{
			returnClassName = Void.class.getName();
		}

		for (String paramClassName : paramClassesName)
		{
			msp.paramTypeList.add(ParseUtil.expandParameterType(paramClassName));
		}

		msp.returnType = returnClassName;
	}

	private Map<String, String> buildGenerics(String genericsString)
	{
		Map<String, String> result = new LinkedHashMap<>();
		String stripped = genericsString.substring(1, genericsString.length() - 1);
		String[] substitutions = stripped.split(S_COMMA);

		for (String sub : substitutions)
		{
			sub = sub.replace(S_SLASH, S_DOT); // in package names

			if (sub.contains(" extends "))
			{
				String[] pair = sub.split(" extends ");
				String child = pair[0].trim();
				String parent = pair[1].trim();
				result.put(child, parent);
			}
			else
			{
				result.put(sub, null);
			}
		}
		return result;
	}

	private void buildParamTypes(String paramString, ClassBC classBC, Map<String, String> methodGenericsMap)
	{
		int angleBracketDepth = 0;

		String stripped = paramString.substring(1, paramString.length() - 1);

		if (stripped.length() > 0)
		{
			StringBuilder paramBuilder = new StringBuilder();

			for (int i = 0; i < stripped.length(); i++)
			{
				char c = stripped.charAt(i);

				if (c == C_COMMA)
				{
					if (angleBracketDepth == 0)
					{
						// finished param
						addParam(paramBuilder, classBC, methodGenericsMap);
					}
					else
					{
						paramBuilder.append(c);
					}
				}
				else if (c == C_OPEN_ANGLE)
				{
					angleBracketDepth++;
					paramBuilder.append(c);
				}
				else if (c == C_CLOSE_ANGLE)
				{
					angleBracketDepth--;
					paramBuilder.append(c);
				}
				else
				{
					paramBuilder.append(c);
				}
			}

			// last param
			addParam(paramBuilder, classBC, methodGenericsMap);
		}

	}

	private void addParam(StringBuilder paramBuilder, ClassBC classBC, Map<String, String> methodGenericsMap)
	{
		String param = paramBuilder.toString().trim();

		int lastSpacePos = param.lastIndexOf(C_SPACE);

		if (lastSpacePos != -1)
		{
			boolean first = true;
			boolean validParamName = true;

			// check every character after space to see if valid Java identifier
			for (int i = lastSpacePos + 1; i < param.length(); i++)
			{
				char c = param.charAt(i);

				if (first && !Character.isJavaIdentifierStart(c))
				{
					validParamName = false;
					break;
				}
				else if (!Character.isJavaIdentifierPart(c))
				{
					validParamName = false;
					break;
				}

				first = false;
			}

			if (validParamName)
			{
				param = param.substring(0, lastSpacePos);
			}
		}

		param = replaceGenerics(param, classBC, methodGenericsMap);

		paramTypeList.add(param);
		paramBuilder.delete(0, paramBuilder.length());
	}

	private String replaceGenerics(String value, ClassBC classBC, Map<String, String> methodGenericsMap)
	{
		if (methodGenericsMap != null && methodGenericsMap.containsKey(value))
		{
			return methodGenericsMap.get(value);
		}

		ClassBC curClassBc = classBC;

		do
		{
			Map<String, String> classGenericsMap = curClassBc.getGenericsMap();

			if (classGenericsMap != null && classGenericsMap.containsKey(value))
			{
				return classGenericsMap.get(value);
			}

			curClassBc = curClassBc.getParent();

		} while (curClassBc != null);

		int genericStart = value.indexOf(C_OPEN_ANGLE);

		if (genericStart != -1)
		{
			int genericEnd = value.indexOf(C_CLOSE_ANGLE);
			if (genericEnd != -1)
			{
				if (genericEnd < value.length() - 1)
				{
					return value.substring(0, genericStart) + value.substring(genericEnd + 1);
				}
				return value.substring(0, genericStart);
			}
		}
		return value;
	}

	public int getModifier()
	{
		return modifier;
	}

	public List<String> getModifiers()
	{
		return modifierList;
	}

	public String getReturnType()
	{
		return returnType;
	}

	public String getMemberName()
	{
		return memberName;
	}

	public List<String> getParamTypes()
	{
		return paramTypeList;
	}

	public String getFullyQualifiedClassName()
	{
		return fullyQualifiedClassName;
	}

	public String getPackageName()
	{
		return StringUtil.getAbbreviatedFQName(getFullyQualifiedClassName());
	}

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();

		sb.append("modifiers : ");

		if (modifierList.size() > 0)
		{
			for (String mod : modifierList)
			{
				sb.append(mod).append(C_COMMA);
			}

			sb.deleteCharAt(sb.length() - 1);
		}

		sb.append(C_NEWLINE);

		sb.append("generics  : ");

		sb.append(C_NEWLINE);

		sb.append("class     : ").append(fullyQualifiedClassName).append(C_NEWLINE);

		sb.append("returnType: ").append(returnType).append(C_NEWLINE);

		sb.append("memberName: ").append(memberName).append(C_NEWLINE);

		sb.append("paramTypes: ");

		if (paramTypeList.size() > 0)
		{
			for (String param : paramTypeList)
			{
				sb.append(param).append(C_COMMA);
			}

			sb.deleteCharAt(sb.length() - 1);
		}

		return sb.toString();
	}

	public String toStringSingleLine()
	{
		StringBuilder sb = new StringBuilder();

		sb.append(fullyQualifiedClassName).append(C_DOT).append(memberName).append(S_OPEN_PARENTHESES);

		if (paramTypeList.size() > 0)
		{
			for (String param : paramTypeList)
			{
				sb.append(param).append(C_COMMA);
			}

			sb.deleteCharAt(sb.length() - 1);
		}

		sb.append(S_CLOSE_PARENTHESES);

		return sb.toString();
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((fullyQualifiedClassName == null) ? 0 : fullyQualifiedClassName.hashCode());
		result = prime * result + ((memberName == null) ? 0 : memberName.hashCode());
		result = prime * result + modifier;
		result = prime * result + ((modifierList == null) ? 0 : modifierList.hashCode());
		result = prime * result + ((paramTypeList == null) ? 0 : paramTypeList.hashCode());
		result = prime * result + ((returnType == null) ? 0 : returnType.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}

		if (obj == null)
		{
			return false;
		}

		if (getClass() != obj.getClass())
		{
			return false;
		}

		MemberSignatureParts other = (MemberSignatureParts) obj;

		if (fullyQualifiedClassName == null)
		{
			if (other.fullyQualifiedClassName != null)
			{
				return false;
			}
		}
		else if (!fullyQualifiedClassName.equals(other.fullyQualifiedClassName))
		{
			return false;
		}

		if (memberName == null)
		{
			if (other.memberName != null)
			{
				return false;
			}
		}
		else if (!memberName.equals(other.memberName))
		{
			return false;
		}

		/*
		if (modifier != other.modifier)
		{
			return false;
		}

		if (modifierList == null)
		{
			if (other.modifierList != null)
			{
				return false;
			}
		}
		else if (!modifierList.equals(other.modifierList))
		{
			return false;
		}
		 */

		if (paramTypeList == null)
		{
			if (other.paramTypeList != null)
			{
				return false;
			}
		}
		else if (!paramTypeList.equals(other.paramTypeList))
		{
			return false;
		}

		if (returnType == null)
		{
			if (other.returnType != null)
			{
				return false;
			}
		}
		else if (!returnType.equals(other.returnType))
		{
			return false;
		}

		return true;
	}

}
