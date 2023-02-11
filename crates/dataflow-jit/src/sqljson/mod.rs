use crate::{
    codegen::{CodegenConfig, NativeLayout},
    dataflow::CompiledDataflow,
    ir::{
        ColumnType, Constant, FunctionBuilder, Graph, GraphExt, LayoutId, NodeId, RowLayout,
        RowLayoutBuilder, Validator,
    },
    row::Row,
};
use dbsp::{
    trace::{BatchReader, Cursor},
    Runtime,
};
use std::collections::HashMap;

#[cfg(test)]
fn set_column_nullness(row: &mut Row, column: usize, layout: &NativeLayout, null: bool) {
    use crate::codegen::BitSetType;
    use std::mem::align_of;

    let (ty, bit_offset, bit) = layout.nullability_of(column);

    unsafe {
        let bitset = row.as_mut_ptr().add(bit_offset as usize);

        let mut mask = match ty {
            BitSetType::U8 => {
                debug_assert_eq!(bitset as usize % align_of::<u8>(), 0);
                bitset.read() as u64
            }
            BitSetType::U16 => {
                debug_assert_eq!(bitset as usize % align_of::<u16>(), 0);
                bitset.cast::<u16>().read() as u64
            }
            BitSetType::U32 => {
                debug_assert_eq!(bitset as usize % align_of::<u32>(), 0);
                bitset.cast::<u32>().read() as u64
            }
            BitSetType::U64 => {
                debug_assert_eq!(bitset as usize % align_of::<u64>(), 0);
                bitset.cast::<u64>().read()
            }
        };

        if null {
            mask |= 1 << bit;
        } else {
            mask &= !(1 << bit);
        }

        match ty {
            BitSetType::U8 => bitset.write(mask as u8),
            BitSetType::U16 => bitset.cast::<u16>().write(mask as u16),
            BitSetType::U32 => bitset.cast::<u32>().write(mask as u32),
            BitSetType::U64 => bitset.cast::<u64>().write(mask),
        }
    }
}

#[test]
fn test_parse_sql_output() {
    const SQL: &str = include_str!("simple_select.json");

    crate::utils::test_logger();

    let (mut graph, sources, sinks) = parse_sql_output(SQL);
    graph.optimize();
    // Validator::new().validate_graph(&graph).unwrap();

    let (dataflow, jit_handle, layout_cache) =
        CompiledDataflow::new(&graph, CodegenConfig::debug());

    let (mut runtime, (mut inputs, outputs)) =
        Runtime::init_circuit(1, move |circuit| dataflow.construct(circuit)).unwrap();

    let (input_node, input_layout) = sources["T"];
    let (output_node, _output_layout) = sinks["V"];

    let mut values = Vec::new();
    let layout = layout_cache.layout_of(input_layout);
    for (x, y) in [
        (Some(1000), Some(0)),
        (Some(1001), Some(1)),
        (Some(10), Some(2)),
        (None, Some(3)),
        (None, None),
        (Some(2000), None),
    ] {
        unsafe {
            let mut row = Row::uninit(&*jit_handle.vtables()[&input_layout]);

            set_column_nullness(&mut row, 0, &layout, x.is_none());
            if let Some(x) = x {
                row.as_mut_ptr()
                    .add(layout.offset_of(0) as usize)
                    .cast::<i32>()
                    .write(x);
            }

            set_column_nullness(&mut row, 1, &layout, y.is_none());
            if let Some(y) = y {
                row.as_mut_ptr()
                    .add(layout.offset_of(1) as usize)
                    .cast::<i32>()
                    .write(y);
            }

            values.push((row, 1i32));
        }
    }
    inputs
        .get_mut(&input_node)
        .unwrap()
        .as_set_mut()
        .unwrap()
        .append(&mut values);

    runtime.step().unwrap();

    if let Some(output) = outputs[&output_node].as_set() {
        let output = output.consolidate();
        let mut cursor = output.cursor();

        println!("output:");
        while cursor.key_valid() {
            let weight = cursor.weight();
            let key = cursor.key();
            println!("  {key:?}: {weight}");
            cursor.step_key();
        }
    } else {
        unreachable!()
    }

    runtime.kill().unwrap();

    unsafe { jit_handle.free_memory() };
}

/// Returns the dataflow graph, sources and sinks
fn parse_sql_output(
    output: &str,
) -> (
    Graph,
    HashMap<String, (NodeId, LayoutId)>,
    HashMap<String, (NodeId, LayoutId)>,
) {
    let raw = serde_json::from_str::<serde_json::Value>(output).unwrap();

    let mut graph = Graph::new();
    let mut functions = HashMap::new();

    for func in raw["functions"].as_array().unwrap() {
        let name = func["variable"].as_str().unwrap();

        let mut builder = FunctionBuilder::new(graph.layout_cache().clone());

        let mut arg_layouts = Vec::new();
        let args = func["type"]["argumentTypes"][1].as_array().unwrap();
        for arg in args {
            debug_assert_eq!(
                arg["class"].as_str().unwrap(),
                "org.dbsp.sqlCompiler.ir.type.DBSPTypeRef",
            );
            debug_assert_eq!(
                arg["type"]["class"].as_str().unwrap(),
                "org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple",
            );

            let layout = graph.layout_cache().add(sql_layout(&arg["type"]));
            let arg = if arg["mutable"].as_bool().unwrap() {
                builder.add_input_output(layout)
            } else {
                builder.add_input(layout)
            };
            arg_layouts.push(arg);
        }

        let mut output = None;
        let ret_ty = func["type"]["resultType"]["class"].as_str().unwrap();
        if ret_ty == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool" {
            builder.set_return_type(ColumnType::Bool);
        } else if ret_ty == "org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple" {
            let layout = graph
                .layout_cache()
                .add(sql_layout(&func["type"]["resultType"]));
            output = Some(builder.add_output(layout));
        } else {
            todo!("unknown return type: {ret_ty}");
        }

        let func = &func["initializer"];
        let mut variables = HashMap::new();
        let mut variables_null = HashMap::new();

        let params = func["parameters"][1].as_array().unwrap();
        for (param, param_id) in params.iter().zip(arg_layouts) {
            variables.insert(param["pattern"]["identifier"].as_str().unwrap(), param_id);
        }

        let body = dbg!(&dbg!(&dbg!(&func["body"])["contents"])[1])
            .as_array()
            .unwrap();
        dbg!(body);

        for expr in body {
            let var = expr["variable"].as_str().unwrap();

            let initializer = &expr["initializer"];
            let kind = initializer["class"].as_str().unwrap();

            // Loads
            // TODO: Maybe stores to depending on context?
            if kind == "org.dbsp.sqlCompiler.ir.expression.DBSPFieldExpression" {
                let target = initializer["expression"]["variable"].as_str().unwrap();
                let target = variables[target];
                let column = initializer["fieldNo"].as_u64().unwrap() as usize;
                let load = builder.load(target, column);
                variables.insert(var, load);

                if initializer["type"]["mayBeNull"].as_bool().unwrap() {
                    let is_null = builder.is_null(target, column);
                    variables_null.insert(var, is_null);
                }

            // Casts
            } else if kind == "org.dbsp.sqlCompiler.ir.expression.DBSPCastExpression" {
                let src = &initializer["source"];
                let src_var = src["variable"].as_str().unwrap();
                let src_value = variables[src_var];
                let (_src_ty, src_nullable) = sql_type(&src["type"]);
                let (dest_ty, dest_nullable) = sql_type(&initializer["destinationType"]);
                debug_assert_eq!(src_nullable, dest_nullable);

                let cast = if src_nullable {
                    let src_is_null = variables_null[src_var];
                    variables_null.insert(var, src_is_null);
                    builder.cast(src_value, dest_ty)
                } else {
                    builder.cast(src_value, dest_ty)
                };

                variables.insert(var, cast);

            // Binops
            } else if kind == "org.dbsp.sqlCompiler.ir.expression.DBSPBinaryExpression" {
                let lhs_var = initializer["left"]["variable"].as_str().unwrap();
                let rhs_var = initializer["right"]["variable"].as_str().unwrap();
                let (lhs, rhs) = (variables[lhs_var], variables[rhs_var]);

                let operation = initializer["operation"].as_str().unwrap();
                let binop = match operation {
                    "==" => builder.eq(lhs, rhs),
                    "!=" => builder.neq(lhs, rhs),
                    "<" => builder.lt(lhs, rhs),
                    ">" => builder.gt(lhs, rhs),
                    "<=" => builder.le(lhs, rhs),
                    ">=" => builder.ge(lhs, rhs),
                    op => todo!("unknown binop: {op}"),
                };
                variables.insert(var, binop);

                match (variables_null.get(lhs_var), variables_null.get(rhs_var)) {
                    (Some(&lhs_null), Some(&rhs_null)) => {
                        let null = builder.or(lhs_null, rhs_null);
                        variables_null.insert(var, null);
                    }
                    (Some(&null), None) | (None, Some(&null)) => {
                        variables_null.insert(var, null);
                    }
                    (None, None) => {}
                }

            // i32 constants
            // TODO: Handle null constants
            } else if kind == "org.dbsp.sqlCompiler.ir.expression.literal.DBSPI32Literal" {
                let value = initializer["value"].as_i64().unwrap() as i32;
                let constant = builder.constant(Constant::I32(value));
                variables.insert(var, constant);

            // i64 constants
            // TODO: Handle null constants
            } else if kind == "org.dbsp.sqlCompiler.ir.expression.literal.DBSPI64Literal" {
                let value = dbg!(initializer["value"].as_i64().unwrap());
                let constant = builder.constant(Constant::I64(value));
                variables.insert(var, constant);

            // boolean constants
            // TODO: Handle null constants
            } else if kind == "org.dbsp.sqlCompiler.ir.expression.literal.DBSPBoolLiteral" {
                let value = initializer["value"].as_bool().unwrap();
                let constant = builder.constant(Constant::Bool(value));
                variables.insert(var, constant);
            } else {
                todo!("unknown expression body: {expr}")
            }
        }

        let last_expr = &func["body"]["lastExpression"];
        let class = last_expr["class"].as_str().unwrap();
        if class == "org.dbsp.sqlCompiler.ir.expression.DBSPApplyExpression" {
            let func = last_expr["function"]["path"]["components"][1][0]["identifier"]
                .as_str()
                .unwrap();

            if func == "wrap_bool" {
                let arg_name = last_expr["arguments"][1][0]["variable"].as_str().unwrap();
                let arg = variables[arg_name];
                let arg_is_null = variables_null[arg_name];

                let return_bool = builder.create_block();
                let return_null = builder.create_block();
                builder.branch(arg_is_null, return_null, return_bool);
                builder.seal_current();

                // TODO: BB args to a single block instead of making two return blocks
                builder.move_to(return_bool);
                builder.ret(arg);
                builder.seal_current();

                builder.move_to(return_null);
                builder.ret(false);
                builder.seal_current();
            } else {
                todo!("unknown last expression function: {last_expr}")
            }
        } else if class == "org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression" {
            let output = output.unwrap();

            let elements = last_expr["fields"][1]
                .as_array()
                .unwrap()
                .iter()
                .map(|elem| {
                    let var_name = elem["variable"].as_str().unwrap();
                    let value = variables[var_name];
                    let is_null = variables_null.get(var_name).copied();
                    (value, is_null)
                })
                .enumerate();
            for (column, (value, is_null)) in elements {
                builder.store(output, column, value);

                if let Some(is_null) = is_null {
                    builder.set_null(output, column, is_null);
                }
            }

            builder.ret_unit();
            builder.seal_current();
        } else {
            todo!("unknown last expression: {last_expr}")
        }

        let function = builder.build();
        println!("{function:#?}");
        functions.insert(name, function);
    }

    dbg!(&functions);

    let mut sources = HashMap::new();
    let mut sinks = HashMap::new();

    let mut streams = HashMap::new();
    for operator in raw["operators"].as_array().unwrap() {
        let operation = operator["operation"].as_str().unwrap();

        // Source nodes
        if operation.is_empty() {
            let output = operator["output"].as_str().unwrap();

            // TODO: Support other input types
            debug_assert_eq!(
                operator["type"]["class"].as_str().unwrap(),
                "org.dbsp.sqlCompiler.ir.type.DBSPTypeZSet",
            );
            debug_assert!(operator["inputs"].as_array().unwrap().is_empty());

            let key_layout = graph
                .layout_cache()
                .add(sql_layout(&operator["type"]["elementType"]));

            let source = graph.source(key_layout);
            streams.insert(output, source);
            sources.insert(output.to_owned(), (source, key_layout));
        } else if operation == "filter" {
            let input = operator["inputs"][0].as_str().unwrap();
            let input_stream = streams[input];
            let filter_fn = operator["function"]["variable"].as_str().unwrap();
            let output = operator["output"].as_str().unwrap();

            let filter = graph.filter(input_stream, functions[filter_fn].clone());
            streams.insert(output, filter);
        } else if operation == "map" {
            let input = operator["inputs"][0].as_str().unwrap();
            let input_stream = streams[input];
            let layout = graph
                .layout_cache()
                .add(sql_layout(&operator["type"]["elementType"]));
            let map_fn = operator["function"]["variable"].as_str().unwrap();
            let output = operator["output"].as_str().unwrap();

            let map = graph.map(input_stream, layout, functions[map_fn].clone());
            streams.insert(output, map);
        } else if operation == "inspect" {
            let input = operator["inputs"][0].as_str().unwrap();
            let input_stream = streams[input];
            let sink = graph.sink(input_stream);
            let layout = graph
                .layout_cache()
                .add(sql_layout(&operator["type"]["elementType"]));
            let output = operator["output"].as_str().unwrap();
            sinks.insert(output.to_owned(), (sink, layout));
        } else {
            todo!("unknown operator: {operator}")
        }
    }

    (graph, sources, sinks)
}

fn sql_type(ty: &serde_json::Value) -> (ColumnType, bool) {
    let class = ty["class"].as_str().unwrap();
    let nullable = ty["mayBeNull"].as_bool().unwrap();

    let column_ty = if class == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger" {
        let width = ty["width"].as_u64().unwrap();
        match width {
            8 => ColumnType::I8,
            16 => ColumnType::I16,
            32 => ColumnType::I32,
            64 => ColumnType::I64,
            width => todo!("unknown integer width {width}"),
        }
    } else if class == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool" {
        ColumnType::Bool
    } else {
        todo!("unknown type: {ty}")
    };

    (column_ty, nullable)
}

fn sql_layout(fields: &serde_json::Value) -> RowLayout {
    let mut layout = RowLayoutBuilder::new();

    let fields = fields["tupFields"][1].as_array().unwrap();
    for elem in fields {
        let kind = elem["class"].as_str().unwrap();
        let nullable = elem["mayBeNull"].as_bool().unwrap();

        if kind == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger" {
            let ty = match elem["width"].as_u64().unwrap() {
                16 => ColumnType::I16,
                32 => ColumnType::I32,
                64 => ColumnType::I64,
                width => todo!("unknown integer width: {width}"),
            };
            layout.add_column(ty, nullable);
        } else if kind == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeDouble" {
            layout.add_column(ColumnType::F64, nullable);
        } else if kind == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool" {
            layout.add_column(ColumnType::Bool, nullable);
        } else if kind == "org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeString" {
            layout.add_column(ColumnType::String, nullable);
        } else {
            todo!("unknown element type: {kind}")
        }
    }

    dbg!(layout.build())
}
