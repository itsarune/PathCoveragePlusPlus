package parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.microsoft.z3.*;
import common.functions.FunctionContext;
import graph.IfStateNode;
import graph.Node;
import graph.StateNode;

import z3.Z3Solver;

import java.util.*;

import common.functions.Path;

// This is a visitor class that visits the AST nodes and builds the variable map
public class VariableVisitor extends VoidVisitorAdapter<Node> {

    // need to keep the current state of the node instead of passing it as an argument
    // this is because when VoidVisitorAdapter visits nodes other than VariableDeclartion and Assignemnt,
    // the argument is always be initialized to null
    private Node initialNode;
    private Node previousNode;
    private Expression previousCondition;
    private Z3Solver z3Solver;
    // Initialize Z3 context
    Context ctx = new Context();
    // Map for keeping track of parameters for Symbolic Execution
    private static Map<String, Expr> parameterSymbols = new HashMap<>();

    private Path path;
    private FunctionContext functionCtx;

    private ArrayList<Integer> conditionalBlocks;
    Stack<Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>>> paths;
    Stack<ArrayList<Integer>> outerConditionalPath;

    // Keep track of visited lines to avoid overwriting the state updated from if statements by the assignment statements
    private HashSet<Integer> visitedLine = new HashSet<>();

    public VariableVisitor(Node initialNode, Stack<Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>>> paths) {
        this.initialNode = initialNode;
        this.z3Solver = new Z3Solver();
        conditionalBlocks = new ArrayList<>();
        functionCtx = new FunctionContext();
        path = new Path();
        this.paths = paths;
        outerConditionalPath = new Stack<>();
    }

    private void addBinaryExpressionDependencies(BinaryExpr binaryExpr, List<Set<Integer>> dependencies) {
        Set<Integer> binaryExprDependencies = new HashSet<>();
        binaryExpr.getLeft().ifNameExpr(nameExpr ->
                binaryExprDependencies.addAll(this.previousNode.getState().get(nameExpr.getNameAsString())));
        binaryExpr.getRight().ifNameExpr(nameExpr ->
                binaryExprDependencies.addAll(this.previousNode.getState().get(nameExpr.getNameAsString())));
        dependencies.add(binaryExprDependencies);
    }

    public void thenHelper(IfStmt n, Node arg, Expression thenCondition, IfStateNode conditionalNode,  List<Set<Integer>> dependencies) {
        if (this.z3Solver.solve()) {
            StatementVisitor statementVisitor = new StatementVisitor();
            n.getThenStmt().accept(statementVisitor, arg);

            int ifBeginLine = n.getThenStmt().getBegin().get().line;
            int ifEndLine = n.getThenStmt().getEnd().get().line;
            if (paths.empty()) {
                Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>> pathMap = new HashMap<>();
                ArrayList<Integer> ifLines = new ArrayList<>();
                ifLines.add(ifBeginLine);
                ifLines.add(ifEndLine);

                ArrayList<Integer> path = new ArrayList<>();
                statementVisitor.getPath().forEach(line -> path.add(line));
                ArrayList<ArrayList<Integer>> pathList = new ArrayList<>();
                System.out.println("If OuterConditional"+path);//[17, 43] vs [17, 43]
                outerConditionalPath.push(new ArrayList<>(path));
                pathList.add(path);

                pathMap.put(ifLines, pathList);
                paths.push(pathMap);
            } else {
                updateConditionalPath(ifBeginLine, ifEndLine, statementVisitor , "if");
            }

            // Process the 'then' part of the if statement.
            StateNode thenNode = new StateNode(conditionalNode.getState(), dependencies, n.getThenStmt().getBegin().get().line);
            this.previousNode = thenNode;
            this.previousCondition = thenCondition;
            // Visit the 'then' part of the if statement.

            //TODO: This is trying to get the line numbers of the else statement
            n.getThenStmt().accept(this, arg);

            conditionalNode.setThenNode(thenNode);
        }
    }

    private void elseHelper(IfStmt n, Node arg, Expression elseCondition, IfStateNode conditionalNode, List<Set<Integer>> dependencies, Node afterIfNode) {
        if (this.z3Solver.solve()) {
            this.previousCondition = elseCondition;
            Statement elseStmt = n.getElseStmt().get();
            StatementVisitor statementVisitor = new StatementVisitor();
            elseStmt.accept(statementVisitor, arg);
            StateNode elseNode = new StateNode(conditionalNode.getState(), dependencies, elseStmt.getBegin().get().line);
            this.previousNode = elseNode;

            int elseBeginLine = n.getElseStmt().get().getBegin().get().line;
            int elseEndLine = n.getElseStmt().get().getEnd().get().line;

            System.out.println("Else OuterConditional"+statementVisitor.getPath());

            updateConditionalPath(elseBeginLine, elseEndLine, statementVisitor, "else");

            //TODO: This is trying to get the line numbers of the else statement
            elseStmt.accept(this, arg);

            conditionalNode.setElseNode(elseNode);
            // Merge 'then' and 'else' states.
            afterIfNode.setState(afterIfNode.mergeStates(this.previousNode.getState()));
        }
    }

    private void updateConditionalPath(int beginLine, int endLine, StatementVisitor statementVisitor, String pathType) {
        Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>> newOuterConditionalMap = new HashMap<>();
        Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>> outerConditional = paths.pop();
        ArrayList<Integer> key = new ArrayList<>(outerConditional.keySet()).get(0);

        // Common logic for both if and else paths.
        if (key.get(0) < beginLine && key.get(1) > endLine) {
            ArrayList<ArrayList<Integer>> pathList = outerConditional.get(key);
            int pathListSize = pathList.size();

            System.out.println(pathType + " key" + key);
            System.out.println(pathType + " value" + pathList);

            ArrayList<Integer> parentPath;
            System.out.println("17,43 vs empty" + outerConditionalPath); //17,43 vs empty
            if (!outerConditionalPath.isEmpty()) {
                parentPath = new ArrayList<>(pathType.equals("else") ? outerConditionalPath.pop() : outerConditionalPath.peek());
            } else {
                parentPath = new ArrayList<>();
            }
//            System.out.println("Parent Path " + statementVisitor.getPath());
            parentPath.addAll(statementVisitor.getPath()); // empty

//            System.out.println("Parent Path " + pathType + parentPath);

            ArrayList<Integer> currentPath = new ArrayList<>(pathList.get(pathListSize - 1));
            currentPath.addAll(statementVisitor.getPath());
            System.out.println("Current Path " + pathType + currentPath);

            boolean pathsMatch = isPathMatch(parentPath, currentPath, true);

            updateOuterConditional(pathsMatch, pathList, pathListSize, currentPath, parentPath, outerConditional, key);
//
//            if (statementVisitor.getSize() != 0) {
//                updateOuterConditional(pathsMatch, pathList, pathListSize, currentPath, parentPath, outerConditional, key);
//            }
            outerConditional.put(key, pathList);
        } else {
            // Branch specific to if or else based on pathType
            ArrayList<Integer> lines = new ArrayList<>();
            lines.add(beginLine);
            lines.add(endLine);

            ArrayList<Integer> path = new ArrayList<>();
            statementVisitor.getPath().forEach(line -> path.add(line));

            ArrayList<ArrayList<Integer>> pathList = new ArrayList<>();
            pathList.add(path);

            outerConditionalPath.push(new ArrayList<>(path)); // 45 vs 45

            newOuterConditionalMap.put(lines, pathList);
        }

        paths.push(outerConditional);
        if (!newOuterConditionalMap.isEmpty()) {
            paths.push(newOuterConditionalMap);
        }

        System.out.println("Path" + paths);
        System.out.println("OuterConditional" + outerConditionalPath);
    }

    private void updateOuterConditional(boolean pathesMatch, ArrayList<ArrayList<Integer>> pathList, int pathListSize, ArrayList<Integer> currentPath, ArrayList<Integer> parentPath, Map<ArrayList<Integer>, ArrayList<ArrayList<Integer>>> outerConditional, ArrayList<Integer> key) {
        if (pathesMatch) {
            pathList.remove(pathListSize -1);
            pathList.add(pathListSize -1, currentPath);
            System.out.println("192");// correct
            System.out.println(currentPath);
            outerConditionalPath.push(new ArrayList<>(pathList.get(pathListSize -1)));
//            System.out.println("Here 1"+outerConditionalPath);
        } else {
            pathList.add(parentPath);
            outerConditionalPath.push(new ArrayList<>(parentPath)); // different
            System.out.println(pathList.get(pathListSize -1));
//            System.out.println("Here 2"+outerConditionalPath);
        }
        outerConditional.put(key, pathList);
    }

    private static boolean isPathMatch(ArrayList<Integer> parentPath, ArrayList<Integer> currentPath, boolean pathesMatch) {
        if (parentPath.size() == currentPath.size()) {
            for (int i = 0; i< parentPath.size(); i++) {
                if (parentPath.get(i) != currentPath.get(i)) {
                    pathesMatch = false;
                    break;
                }
            }
        } else {
            pathesMatch = false;
        }
        return pathesMatch;
    }

    @Override
    public void visit(IfStmt n, Node arg) {
        Expression originalCondition =previousCondition;
        Expression thenCondition = null;
        if (previousCondition == null) {
            thenCondition = n.getCondition();
        } else {
            thenCondition = new BinaryExpr(previousCondition, n.getCondition(), BinaryExpr.Operator.AND);
        }
        List<Set<Integer>> dependencies = new ArrayList<>(this.previousNode.getDependencies());

        thenCondition.ifBinaryExpr(binaryExpr -> {
            addBinaryExpressionDependencies(binaryExpr, dependencies);
        });

        // Create a new IfStateNode, encapsulating the current state and the extracted condition.
        IfStateNode conditionalNode = new IfStateNode(
                this.previousNode.getState(), dependencies, n.getBegin().get().line, thenCondition
        );
        this.previousNode.setChild(conditionalNode);
        this.z3Solver.setCondition(thenCondition);
        thenHelper(n, arg, thenCondition, conditionalNode, dependencies);

        // Create a copy of the 'then' state to potentially merge with the 'else' state.
        Node afterIfNode = new StateNode();
        afterIfNode.setState(this.previousNode.getState());

//        System.out.println("Step 6 Else Condition");
        // If an 'else' part exists, process it similarly.
        if(n.getElseStmt().isPresent()) {
//            System.out.println("Step 7 Else Condition");
            System.out.println(outerConditionalPath); // [[41]] vs [[17, 43, 41]]
            System.out.println( outerConditionalPath.pop());
            Expression elseCondition = null;
            if(originalCondition == null) {
                elseCondition = new UnaryExpr(n.getCondition(), UnaryExpr.Operator.LOGICAL_COMPLEMENT);
            } else {
                elseCondition = new BinaryExpr(originalCondition, new UnaryExpr(n.getCondition(), UnaryExpr.Operator.LOGICAL_COMPLEMENT), BinaryExpr.Operator.AND);
            }
            this.z3Solver.setCondition(elseCondition);
            elseHelper(n, arg, elseCondition, conditionalNode, dependencies, afterIfNode);
        }

        // Clean up by removing the last set of dependencies after leaving the if statement.
        List<Set<Integer>> originalDependencies = new ArrayList<>(this.previousNode.getDependencies());
        if (!originalDependencies.isEmpty()) {
            originalDependencies.remove(conditionalNode.getDependencies().size() - 1);
        }

//        System.out.println("Step 9 Visited Line");
        // Add the lines visited by the if statement to the visitedLine set to avoid overwriting the state
        for (int i = n.getBegin().get().line; i <= n.getEnd().get().line; i++) {
            visitedLine.add(i);
        }

        // Set the dependencies of the afterIfNode to the original dependencies
        afterIfNode.setDependencies(originalDependencies);
        afterIfNode.setLineNumber(n.getEnd().get().line);
        conditionalNode.setChild(afterIfNode);

        this.previousCondition = originalCondition;
        this.previousNode = afterIfNode;
        System.out.println("Path " + paths);
    }


    @Override
    public void visit(VariableDeclarationExpr n, Node arg) {
        // Process the node to update the state with variable declarations
        n.getVariables().forEach(var -> {
            ArrayList<Integer> lines = new ArrayList<Integer>();
            String variableName = var.getNameAsString();

            Expr rhsExpr = null;
            if (var.getInitializer().isPresent()) {
                // Evaluate the expression and update the map
                rhsExpr = evaluateExpression(var.getInitializer().get(), parameterSymbols, this.ctx);
                parameterSymbols.put(variableName, rhsExpr);
            }

            int line = var.getBegin().map(pos -> pos.line).orElse(-1); // Use -1 to indicate unknown line numbers
            lines.add(line);
            processAssignStaticValue(variableName, var.getInitializer().get());
            previousNode = processNode(variableName, lines, previousNode);
        });
    }

    @Override
    public void visit(AssignExpr n, Node arg) {
        ArrayList<Integer> lines = new ArrayList<Integer>();
        String variableName = n.getTarget().toString();
        String valueName = n.getValue().toString();

        Expr rhsExpr = convertToZ3Expr(n.getValue(), ctx, parameterSymbols); // Implement this method

        // Update the symbolic state with the new expression
//        parameterSymbols.put(variableName, rhsExpr);

        updateSymbolMapWithAssignment(n, parameterSymbols, ctx);

        System.out.println("!!!!!!" + variableName + n.getValue().toString());
        int line = n.getBegin().map(pos -> pos.line).orElse(-1); // Same use of -1 for unknown line numbers
        lines.add(line);
        Set<Integer> valLineNumbers = this.assignValLineNumbers(lines, valueName);
        if (!valLineNumbers.isEmpty()) {
            lines.addAll(valLineNumbers);
        }
        processAssignStaticValue(variableName, n.getValue());
        previousNode = processNode(variableName, lines, previousNode);
    }

    private void updateSymbolMapWithAssignment(AssignExpr assignExpr, Map<String, Expr> symbolMap, Context ctx) {
        String targetVar = assignExpr.getTarget().toString();
        Expr evaluatedExpr = evaluateExpression(assignExpr.getValue(), symbolMap, ctx);
        symbolMap.put(targetVar, evaluatedExpr); // Update the map with the new or updated symbolic expression
    }

    private Expr evaluateExpression(Expression expr, Map<String, Expr> symbolMap, Context ctx) {
        if (expr instanceof IntegerLiteralExpr) {
            int value = ((IntegerLiteralExpr) expr).asInt();
            return ctx.mkInt(value); // Direct static value
        } else if (expr instanceof NameExpr) {
            String varName = ((NameExpr) expr).getNameAsString();
            return symbolMap.getOrDefault(varName, ctx.mkIntConst(varName)); // Existing symbol or new symbol for uninitialized variable
        } else if (expr instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) expr;
            Expr left = evaluateExpression(binaryExpr.getLeft(), symbolMap, ctx);
            Expr right = evaluateExpression(binaryExpr.getRight(), symbolMap, ctx);
            switch (binaryExpr.getOperator()) {
                case PLUS:
                    return ctx.mkAdd(new Expr[]{left, right});
                case MINUS:
                    return ctx.mkSub(new Expr[]{left, right});
                // Handle other operators as needed
            }
        }
        // Extend to handle more expression types as needed
        return null; // Placeholder to satisfy return requirement
    }

    // Convert JavaParser Expression to Z3 Expr, handling addition and subtraction
    private Expr convertToZ3Expr(com.github.javaparser.ast.expr.Expression expression, Context ctx, Map<String, Expr> symbolMap) {
        if (expression instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expression;
            Expr left = convertToZ3Expr(binExpr.getLeft(), ctx, symbolMap);
            Expr right = convertToZ3Expr(binExpr.getRight(), ctx, symbolMap);

            switch (binExpr.getOperator()) {
                case PLUS:
                    return ctx.mkAdd(new IntExpr[]{(IntExpr) left, (IntExpr) right});
                case MINUS:
                    return ctx.mkSub(new IntExpr[]{(IntExpr) left, (IntExpr) right});
                // Handle other operators as needed
            }
        } else if (expression.isNameExpr()) {
            // Variable reference
            String varName = expression.asNameExpr().getNameAsString();
            return symbolMap.getOrDefault(varName, ctx.mkIntConst(varName)); // Assume integer for simplicity
        } else if (expression.isIntegerLiteralExpr()) {
            // Concrete integer value
            int value = expression.asIntegerLiteralExpr().asInt();
            return ctx.mkInt(value);
        }
        // Extend to handle other expression types as needed
        throw new UnsupportedOperationException("Unsupported expression type: " + expression.getClass());
    }

    @Override
    public void visit(MethodDeclaration n, Node arg) {
        ArrayList<Integer> lines = new ArrayList<Integer>();
        n.getParameters().forEach(parameter -> {
            String variableName = parameter.getNameAsString();
            System.out.println("!!!!!!!!!" + variableName);
            Sort paramSort = getTypeSort(parameter.getType(), ctx);
            // Create a Z3 symbol for the parameter name
            Symbol paramSymbol = ctx.mkSymbol(variableName);
            // Create a Z3 constant (symbolic variable) for the parameter
            Expr paramExpr = ctx.mkConst(paramSymbol, paramSort);
            parameterSymbols.put(variableName, paramExpr);
            int line = n.getBegin().get().line;
            lines.add(line);
            previousNode = processNode(variableName, lines, previousNode);
        });
        n.getBody().ifPresent(body -> body.accept(this, arg));
    }

    private static Sort getTypeSort(com.github.javaparser.ast.type.Type type, Context ctx) {
        // Example: Determine the sort based on the simple name of the type
        String typeName = type.toString();
        switch (typeName) {
            case "int":
                return ctx.getIntSort();
            case "boolean":
                return ctx.getBoolSort();
            // Add more cases for other types
            default:
                throw new IllegalArgumentException("Unsupported type: " + typeName);
        }
    }

    private void processAssignStaticValue(String variableName, Expression value) {
//        System.out.println(value);
//        this.z3Solver.setCondition(previousCondition);
//        boolean thenCondition = this.z3Solver.solve();
//        boolean else
//        if( this.z3Solver.solve()) {
//            this.z3Solver.removeStaticVariable(variableName);
//            return;
//        }
        if (value.isBooleanLiteralExpr() || value.isIntegerLiteralExpr() || value.isUnaryExpr() || value.isBinaryExpr()) {
            this.z3Solver.addStaticVariableValues(variableName, value);
            System.out.println(this.z3Solver.getStaticVariableValues());
        } else {
            // means the value is a variable
            if(this.z3Solver.isVariableValueKnown(value.toString())){
                this.z3Solver.addStaticVariableValues(variableName, this.z3Solver.getVariableValue(value.toString()));
            } else {
                if (previousCondition != null) {
                    this.z3Solver.setCondition(previousCondition);
                    boolean thenCondition = this.z3Solver.solve();
                    System.out.println(new UnaryExpr(previousCondition, UnaryExpr.Operator.LOGICAL_COMPLEMENT));
                    this.z3Solver.setCondition(new UnaryExpr(previousCondition, UnaryExpr.Operator.LOGICAL_COMPLEMENT));
                    boolean elseCondition = this.z3Solver.solve();

                    // variableName is dynamically determined
                    if(thenCondition && elseCondition) {
                        this.z3Solver.removeStaticVariable(variableName);
                    }

                } else {
                    // variableName is dynamically determined
                    this.z3Solver.removeStaticVariable(variableName);
                }

            }
        }
    }


    // REQUIRES: lines[0] is the current line number always
    private Node processNode(String variableName, ArrayList<Integer> lines, Node parent) {
        if (parent == null) {
            parent = initialNode;
        }
        if (visitedLine.contains(lines.get(0))) {
            return parent;
        }
        Map<String, Set<Integer>> currentState = parent.getState();
//        System.out.println("current state" + currentState);
        if (!currentState.containsKey(variableName) || !currentState.get(variableName).contains(lines.get(0))) {

            Map<String, Set<Integer>> newState = new HashMap<>();
            for (Map.Entry<String, Set<Integer>> entry : currentState.entrySet()) {
                newState.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            // Add the new variable assignment to the state
            // computeIfAbsent is a method that returns the value of the specified key in the map

            for (int l : lines) {
                newState.computeIfAbsent(variableName, k -> new HashSet<>()).add(l);
            }

            Node newNode = new StateNode();

            // If the variable has dependencies (so in if block), add the dependencies to the new node state
            if (parent.getDependencies().size() > 0) {
                List<Set<Integer>> dependencies = new ArrayList<>(parent.getDependencies());
                for (Set<Integer> dependency : dependencies) {
                    newState.computeIfAbsent(variableName, k -> new HashSet<>()).addAll(dependency);
                }
            }
            newNode.setState(newState);
            newNode.setLineNumber(lines.get(0));
            newNode.setDependencies(parent.getDependencies());

            parent.setChild(newNode);
            return newNode;
        }

        return parent;
    }

    private Set<Integer> assignValLineNumbers(ArrayList<Integer> lines, String valueName) {
        Map<String, Set<Integer>> currentState;
        if (previousNode == null) {
            currentState = initialNode.getState();
        } else {
            currentState = previousNode.getState();
        }
        if (currentState.containsKey(valueName)) {
            return currentState.get(valueName);
        }
        return new HashSet<>();
    }

}
