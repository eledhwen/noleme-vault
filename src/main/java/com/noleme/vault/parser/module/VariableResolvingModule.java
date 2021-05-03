package com.noleme.vault.parser.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noleme.json.Json;
import com.noleme.vault.container.definition.Definitions;
import com.noleme.vault.container.definition.Definitions.Variables;
import com.noleme.vault.container.definition.Variable;
import com.noleme.vault.exception.VaultParserException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Pierre Lecerf (plecerf@lumiomedical.com)
 * Created on 2020/11/26
 */
public class VariableResolvingModule implements VaultModule
{
    /* TODO: env:
     * - handle default values
     * - handle injection failure within container -> parseInt, parseFloat, etc.
     * - handle injection failure outside container -> ???
     */
    private static final Pattern variablePattern = Pattern.compile("(##(.*?)##)");
    private static final Pattern envPattern = Pattern.compile("(\\$\\{([A-Za-z0-9_.-].*?)(-(.*?))?})");

    @Override
    public String identifier()
    {
        return "variables";
    }

    @Override
    public void process(ObjectNode json, Definitions definitions) throws VaultParserException
    {
        Map<String, Variable> variables = new HashMap<>();
        Queue<Variable> queue = new LinkedList<>();
        List<Variable> heap = new ArrayList<>();
        List<String> sorted = new ArrayList<>();

        for (Map.Entry<String, Object> varDef : definitions.getVariables().dictionary().entrySet())
        {
            Variable var = new Variable(
                varDef.getKey(),
                varDef.getValue(),
                varDef.getValue() instanceof String
                    ? findVariables((String)varDef.getValue())
                    : Collections.emptyList()
            );

            variables.put(varDef.getKey(), var);

            if (!var.hasDependencies())
                queue.add(var);
            else
                heap.add(var);
        }

        while (!queue.isEmpty())
        {
            Variable variable = queue.poll();
            sorted.add(variable.getName());

            Iterator<Variable> it = heap.iterator();
            V_SEARCH: while (it.hasNext())
            {
                Variable v = it.next();
                for (String dep : v.getDependencies())
                {
                    if (!sorted.contains(dep))
                        continue V_SEARCH;
                }
                queue.add(v);
                it.remove();
            }
        }

        if (!heap.isEmpty())
            throw new VaultParserException("The variable definitions contain a circular reference involving "+heap.size()+" variables.");

        for (String vk : sorted)
        {
            Variable v = variables.get(vk);

            if (!(v.getValue() instanceof String))
                continue;

            String val = (String)v.getValue();
            for (String dep : v.getDependencies())
            {
                var value = variables.get(dep).getValue();
                val = val.replace("##"+dep+"##", value != null ? value.toString() : "");
            }
            val = replaceEnv(val);

            v.setValue(val);
            definitions.getVariables().set(vk, val);
        }
    }

    /**
     *
     * @param string
     * @param variables
     * @return
     */
    private static Map<String, Object> findReplacements(String string, Variables variables) throws VaultParserException
    {
        Map<String, Object> replacements = new HashMap<>();

        for (String var : findVariables(string))
        {
            if (!variables.has(var))
                throw new VaultParserException("The requested variable '"+var+"' could not be found in the container.");
            replacements.put("##"+var+"##", variables.get(var));
        }

        return replacements;
    }

    /**
     *
     * @param string
     * @return
     */
    private static Collection<String> findVariables(String string)
    {
        List<String> variables = new ArrayList<>();

        Matcher matcher = variablePattern.matcher(string);
        if (matcher.find())
        {
            variables.add(matcher.group(2));

            matcher.results().forEach(matchResult -> {
                String variable = matchResult.group(2);
                variables.add(variable);
            });
        }

        return variables;
    }

    /**
     *
     * @param string
     * @param definitions
     * @return
     */
    public static JsonNode replace(String string, Definitions definitions) throws VaultParserException
    {
        String newString = string;
        for (Map.Entry<String, Object> replacement : findReplacements(string, definitions.getVariables()).entrySet())
        {
            if (replacement.getValue() == null || newString.length() == replacement.getKey().length())
                return Json.toJson(replacement.getValue());
            else
                newString = newString.replace(replacement.getKey(), replacement.getValue().toString());
        }
        newString = replaceEnv(newString);

        return Json.toJson(newString);
    }

    /**
     *
     * @param value
     * @return
     */
    private static String replaceEnv(String value)
    {
        Matcher matcher = envPattern.matcher(value);
        if (matcher.find())
        {
            return matcher.replaceAll(mr -> {
                String variable = mr.group(2);
                String defaultValue = mr.group(4);

                if (variable == null)
                    return "";

                String env = System.getenv(variable);

                if (env == null)
                    env = defaultValue;

                return env != null ? env : "";
            });
        }
        return value;
    }
}
