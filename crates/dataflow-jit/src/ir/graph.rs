use crate::ir::{
    layout_cache::LayoutCache,
    node::{DataflowNode, Node},
    NodeId, NodeIdGen,
};
use std::collections::BTreeMap;

#[derive(Debug)]
pub struct Graph {
    nodes: BTreeMap<NodeId, Node>,
    layout_cache: LayoutCache,
    node_id: NodeIdGen,
}

impl Graph {
    pub fn new() -> Self {
        Self {
            nodes: BTreeMap::new(),
            layout_cache: LayoutCache::new(),
            node_id: NodeIdGen::new(),
        }
    }

    pub const fn nodes(&self) -> &BTreeMap<NodeId, Node> {
        &self.nodes
    }

    pub fn add_node<N>(&mut self, node: N) -> NodeId
    where
        N: Into<Node>,
    {
        let node = node.into();
        let node_id = self.node_id.next();
        self.nodes.insert(node_id, node);
        node_id
    }

    pub fn layout_cache(&self) -> &LayoutCache {
        &self.layout_cache
    }

    pub fn optimize(&mut self) {
        for node in self.nodes.values_mut() {
            node.optimize(&[], &self.layout_cache);
        }
    }
}

impl Default for Graph {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use crate::{
        codegen::{Codegen, Layout, NullSigil},
        ir::{
            expr::{Constant, CopyRowTo, NullRow},
            function::FunctionBuilder,
            graph::Graph,
            node::{Differentiate, Fold, IndexWith, Map, Neg, Node, Sink, Source, Sum},
            types::{RowLayout, RowLayoutBuilder, RowType},
            validate::Validator,
        },
    };
    use cranelift::codegen::{
        isa,
        settings::{self, Configurable},
    };
    use dbsp::{
        operator::FilterMap,
        trace::{BatchReader, Cursor},
        Runtime,
    };
    use std::{collections::BTreeMap, sync::Arc};
    use target_lexicon::Triple;

    // ```sql
    // CREATE VIEW V AS SELECT Sum(r.COL1 * r.COL5)
    // FROM T r
    // WHERE 0.5 * (SELECT Sum(r1.COL5) FROM T r1)
    //     = (SELECT Sum(r2.COL5) FROM T r2 WHERE r2.COL1 = r.COL1)
    // ```
    #[test]
    fn complex() {
        let mut graph = Graph::new();

        let unit_layout = graph.layout_cache().unit();
        let weight_layout = graph.layout_cache().add(RowLayout::weight());
        let nullable_i32 = graph
            .layout_cache()
            .add(RowLayoutBuilder::new().with_row(RowType::I32, true).build());

        // let T = circuit.add_source(T);
        let source_row = graph.layout_cache().add(
            RowLayoutBuilder::new()
                .with_row(RowType::I32, false)
                .with_row(RowType::F64, false)
                .with_row(RowType::Bool, false)
                .with_row(RowType::String, false)
                .with_row(RowType::I32, true)
                .with_row(RowType::F64, true)
                .build(),
        );
        let source = graph.add_node(Source::new(source_row));

        // ```
        // let stream7850: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> =
        //     T.map(move |t: &Tuple6<i32, F64, bool, String, Option<i32>, Option<F64>>| -> Tuple1<Option<i32>> {
        //         Tuple1::new(t.4)
        //     });
        // ```
        let stream7850 = graph.add_node(Map::new(
            source,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(source_row);
                let output = func.add_mut_input(nullable_i32);

                // Set the output row to null if the input value is null
                let value_is_null = func.is_null(input, 4);
                func.set_null(output, 0, value_is_null);

                // Copy the value to the output row
                let value = func.extract(input, 4);
                func.insert(output, 0, value);

                func.ret_unit();
                func.build()
            },
            nullable_i32,
        ));

        // ```
        // let stream7856: Stream<_, OrdIndexedZSet<(), Tuple1<Option<i32>>, Weight>> =
        //     stream7850.index_with(move |t: &Tuple1<Option<i32>>| -> ((), Tuple1<Option<i32>>) {
        //         ((), Tuple1::new(t.0))
        //     });
        // ```
        let stream7856 = graph.add_node(IndexWith::new(
            stream7850,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(nullable_i32);
                let key = func.add_mut_input(unit_layout);
                let value = func.add_mut_input(nullable_i32);

                func.insert(key, 0, Constant::Unit);

                // Set the output row to null if the input value is null
                let value_is_null = func.is_null(input, 0);
                func.set_null(value, 0, value_is_null);

                // Copy the value to the output row
                let input_val = func.extract(input, 0);
                func.insert(value, 0, input_val);

                func.ret_unit();
                func.build()
            },
            unit_layout,
            nullable_i32,
        ));

        // ```
        // let stream7861: Stream<_, OrdIndexedZSet<(), Tuple1<Option<i32>>, Weight>> =
        //     stream7856.aggregate::<(), _>(
        //         Fold::<_, UnimplementedSemigroup<Tuple1<Option<i32>>>, _, _>::with_output(
        //             (None::<i32>, ),
        //             move |a: &mut (Option<i32>,), v: &Tuple1<Option<i32>>, w: Weight| {
        //                 *a = (move |a: Option<i32>, v: &Tuple1<Option<i32>>, w: Weight| -> Option<i32> {
        //                     agg_plus_N_N(a, v.0.mul_by_ref(&w))
        //                 }(a.0, v, w),)
        //             },
        //             move |a: (Option<i32>,)| -> Tuple1<Option<i32>> {
        //                 Tuple1::new(identity::<Option<i32>>(a.0))
        //             },
        //         ),
        //     );
        // ```
        let stream7861 = graph.add_node(Fold::new(
            stream7856,
            NullRow::new(nullable_i32).into(),
            // FIXME: Fully move over to mutable `acc` instead of a return value
            // Equivalent to
            // ```rust
            // fn(mut acc, current, weight) {
            //     set_null(acc, is_null(acc) & is_null(current));
            //
            //     if is_null(current) {
            //         return;
            //     } else {
            //         let diff = current * weight;
            //         if is_null(acc) {
            //             insert diff into acc;
            //             return;
            //         } else {
            //             let sum = acc + diff;
            //             insert sum into acc;
            //             return
            //         }
            //     }
            // }
            // ```
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let accumulator = func.add_mut_input(nullable_i32);
                let current = func.add_input(nullable_i32);
                let weight = func.add_input(weight_layout);

                let acc_is_null = func.is_null(accumulator, 0);
                let current_is_null = func.is_null(current, 0);
                let acc_or_current_null = func.and(acc_is_null, current_is_null);
                func.set_null(accumulator, 0, acc_or_current_null);

                let current_null = func.create_block();
                let current_non_null = func.create_block();
                func.branch(current_is_null, current_null, current_non_null);

                func.move_to(current_null);
                func.ret_unit();
                func.seal();

                let acc_null = func.create_block();
                let acc_non_null = func.create_block();

                func.move_to(current_non_null);
                let current = func.extract(current, 0);
                let weight = func.extract(weight, 0);
                let diff = func.mul(current, weight);
                func.branch(acc_is_null, acc_null, acc_non_null);
                func.seal();

                func.move_to(acc_null);
                func.insert(accumulator, 0, diff);
                func.ret_unit();
                func.seal();

                func.move_to(acc_non_null);
                let acc = func.extract(accumulator, 0);
                let sum = func.add(acc, diff);
                func.insert(accumulator, 0, sum);
                func.ret_unit();
                func.seal();

                func.build()
            },
            // Just a unit closure
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(nullable_i32);
                let output = func.add_mut_input(nullable_i32);

                // Set the output row to null if the input value is null
                let value_is_null = func.is_null(input, 0);
                func.set_null(output, 0, value_is_null);

                // Unconditionally copy the value into the output row
                let value = func.extract(input, 0);
                func.insert(output, 0, value);

                func.ret_unit();
                func.seal();

                func.build()
            },
            nullable_i32,
            nullable_i32,
            nullable_i32,
        ));

        // ```
        // let stream7866: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> =
        //     stream7861.map(move |(k, v): (&(), &Tuple1<Option<i32>>)| -> Tuple1<Option<i32>> {
        //         Tuple1::new(v.0)
        //     });
        // ```
        let stream7866_layout = graph.layout_cache().add(
            RowLayoutBuilder::new()
                .with_row(RowType::Unit, false)
                .with_row(RowType::I32, true)
                .build(),
        );
        let stream7866 = graph.add_node(Map::new(
            stream7861,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(stream7866_layout);
                let output = func.add_mut_input(nullable_i32);

                // Set the output row to null if the input value is null
                let value_is_null = func.is_null(input, 1);
                func.set_null(output, 0, value_is_null);

                // Unconditionally copy the value into the output row
                let value = func.extract(input, 1);
                func.insert(output, 0, value);

                func.ret_unit();
                func.seal();

                func.build()
            },
            nullable_i32,
        ));

        // ```
        // let stream7874: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> =
        //     stream7866.map(move |_t: _| -> Tuple1<Option<i32>> {
        //         Tuple1::new(None::<i32>)
        //     });
        // ```
        let stream7874 = graph.add_node(Map::new(
            stream7866,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let _input = func.add_input(nullable_i32);
                let output = func.add_mut_input(nullable_i32);

                func.set_null(output, 0, Constant::Bool(true));
                func.ret_unit();
                func.build()
            },
            nullable_i32,
        ));

        // ```
        // let stream7879: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> = stream7874.neg();
        // ```
        let stream7879 = graph.add_node(Neg::new(stream7874, nullable_i32));

        // TODO: Constant sources/generators
        // ```
        // let stream7130 = circuit.add_source(Generator::new(|| zset!(
        //     Tuple1::new(None::<i32>) => 1,
        // )));
        // ```
        let stream7130 = graph.add_node(Source::new(nullable_i32));

        // ```
        // let stream7883: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> = stream7130.differentiate();
        // ```
        let stream7883 = graph.add_node(Differentiate::new(stream7130));

        // ```
        // let stream7887: Stream<_, OrdZSet<Tuple1<Option<i32>>, Weight>> = stream7883.sum([&stream7879, &stream7866]);
        // ```
        let stream7887 = graph.add_node(Sum::new(vec![stream7883, stream7879, stream7866]));

        // ```
        // let stream7892: Stream<_, OrdIndexedZSet<(), Tuple6<i32, F64, bool, String, Option<i32>, Option<F64>>, Weight>> =
        //     T.index_with(move |l: &Tuple6<i32, F64, bool, String, Option<i32>, Option<F64>>| -> ((), Tuple6<i32, F64, bool, String, Option<i32>, Option<F64>>) {
        //         ((), Tuple6::new(l.0, l.1, l.2, l.3.clone(), l.4, l.5))
        //     });
        // ```
        let stream7892 = graph.add_node(IndexWith::new(
            source,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(source_row);
                let key = func.add_mut_input(unit_layout);
                let value = func.add_mut_input(source_row);

                func.insert(key, 0, Constant::Unit);

                func.add_expr(CopyRowTo::new(input, value, source_row));
                func.ret_unit();
                func.build()
            },
            unit_layout,
            source_row,
        ));

        // ```
        // let stream7897: Stream<_, OrdIndexedZSet<(), Tuple1<Option<i32>>, Weight>> =
        //     stream7887.index_with(move |r: &Tuple1<Option<i32>>| -> ((), Tuple1<Option<i32>>) {
        //         ((), Tuple1::new(r.0))
        //     });
        // ```
        let stream7897 = graph.add_node(IndexWith::new(
            stream7887,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(nullable_i32);
                let key = func.add_mut_input(unit_layout);
                let value = func.add_mut_input(nullable_i32);

                func.insert(key, 0, Constant::Unit);

                func.add_expr(CopyRowTo::new(input, value, nullable_i32));
                func.ret_unit();
                func.build()
            },
            unit_layout,
            nullable_i32,
        ));

        println!("Pre-opt: {graph:#?}");
        graph.optimize();
        println!("Post-opt: {graph:#?}");

        {
            let target_isa = isa::lookup(Triple::host())
                .unwrap()
                .finish(settings::Flags::new(settings::builder()))
                .unwrap();
            let frontend_config = target_isa.frontend_config();

            for row_layout in graph.layout_cache().layouts().iter() {
                let layout = Layout::from_row(row_layout, &frontend_config);
                println!("{layout:?}");
            }

            let mut codegen =
                Codegen::new(target_isa, graph.layout_cache().clone(), NullSigil::One);
            for node in graph.nodes.values() {
                println!("{node:?}");

                match node {
                    Node::Map(map) => {
                        codegen.codegen_func(map.map_fn());
                    }
                    Node::Neg(_) => {}
                    Node::Sum(_) => {}
                    Node::Fold(fold) => {
                        codegen.codegen_func(fold.step_fn());
                        codegen.codegen_func(fold.finish_fn());
                    }
                    Node::Sink(_) => {}
                    Node::Source(_) => {}
                    Node::Filter(filter) => {
                        codegen.codegen_func(filter.filter_fn());
                    }
                    Node::IndexWith(index_with) => {
                        codegen.codegen_func(index_with.index_fn());
                    }
                    Node::Differentiate(_) => {}
                }
            }
        }
    }

    #[test]
    fn mapping() {
        let mut graph = Graph::new();

        let xy_layout = graph.layout_cache().add(
            RowLayoutBuilder::new()
                .with_row(RowType::U32, false)
                .with_row(RowType::U32, false)
                .build(),
        );
        let source = graph.add_node(Source::new(xy_layout));

        let x_layout = graph.layout_cache().add(
            RowLayoutBuilder::new()
                .with_row(RowType::U32, false)
                .build(),
        );
        let map = graph.add_node(Map::new(
            source,
            {
                let mut func = FunctionBuilder::new(graph.layout_cache().clone());
                let input = func.add_input(xy_layout);
                let output = func.add_mut_input(x_layout);

                let x = func.extract(input, 0);
                let y = func.extract(input, 1);
                let xy = func.mul(x, y);
                func.insert(output, 0, xy);

                func.ret_unit();
                func.build()
            },
            x_layout,
        ));

        let sink = graph.add_node(Sink::new(map));

        let mut validator = Validator::new();
        validator.validate_graph(&graph);

        graph.optimize();

        validator.validate_graph(&graph);

        let mut settings = settings::builder();

        let options = &[
            ("opt_level", "speed"),
            ("use_egraphs", "true"),
            ("enable_simd", "true"),
            ("enable_verifier", "true"),
            ("enable_jump_tables", "true"),
            ("enable_alias_analysis", "true"),
            ("use_colocated_libcalls", "false"),
            // FIXME: Set back to true once the x64 backend supports it.
            ("is_pic", "false"),
        ];
        for (name, value) in options {
            settings.set(name, value).unwrap();
        }

        let target_isa = isa::lookup(Triple::host())
            .unwrap()
            .finish(settings::Flags::new(settings))
            .unwrap();

        let mut codegen = Codegen::new(target_isa, graph.layout_cache().clone(), NullSigil::One);

        let src_layout = codegen.layout_for(xy_layout);
        let (src_x_offset, src_y_offset) = (src_layout.row_offset(0), src_layout.row_offset(1));
        let src_layout = std::alloc::Layout::from_size_align(
            src_layout.size() as usize,
            src_layout.align() as usize,
        )
        .unwrap();

        let sink_layout = codegen.layout_for(x_layout);
        let sink_x_offset = sink_layout.row_offset(0);
        let sink_layout = std::alloc::Layout::from_size_align(
            sink_layout.size() as usize,
            sink_layout.align() as usize,
        )
        .unwrap();

        let node_functions: BTreeMap<_, _> = graph
            .nodes
            .iter()
            .filter_map(|(&node_id, node)| {
                if let Node::Map(map) = node {
                    let func_id = codegen.codegen_func(map.map_fn());
                    let layout = codegen.layout_for(map.layout());
                    Some((node_id, (func_id, layout.size(), layout.align())))
                } else {
                    None
                }
            })
            .collect();
        let jit_module = codegen.finalize_definitions();
        let node_functions: BTreeMap<_, _> = node_functions
            .into_iter()
            .map(|(node_id, (func_id, size, align))| {
                (
                    node_id,
                    (
                        Ptr(jit_module.get_finalized_function(func_id) as *mut u8),
                        size,
                        align,
                    ),
                )
            })
            .collect();

        #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, size_of::SizeOf)]
        #[repr(transparent)]
        struct Ptr(*mut u8);

        unsafe impl Send for Ptr {}
        unsafe impl Sync for Ptr {}

        let nodes = Arc::new(graph.nodes);
        let (mut runtime, (mut inputs, outputs)) =
            Runtime::init_circuit(1, move |circuit| {
                let mut streams = BTreeMap::new();
                let mut inputs = BTreeMap::new();
                let mut outputs = BTreeMap::new();

                for (&node_id, node) in nodes.iter() {
                    match node {
                        Node::Source(_source) => {
                            let (stream, handle) = circuit.add_input_zset::<Ptr, i32>();
                            streams.insert(node_id, stream);
                            inputs.insert(node_id, handle);
                        }

                        Node::Sink(sink) => {
                            let output = streams[&sink.input()].output();
                            outputs.insert(node_id, output);
                        }

                        Node::Map(map) => {
                            let input = &streams[&map.input()];

                            let (map_fn, size, align) = node_functions[&node_id];
                            let map_fn = unsafe {
                                std::mem::transmute::<
                                    *const u8,
                                    unsafe extern "C" fn(*const u8, *mut u8),
                                >(map_fn.0)
                            };

                            let layout =
                                std::alloc::Layout::from_size_align(size as usize, align as usize)
                                    .unwrap();
                            let stream = input.map(move |&x| {
                                let output = unsafe { std::alloc::alloc(layout) };
                                assert!(!output.is_null());
                                unsafe { map_fn(x.0, output) };
                                Ptr(output)
                            });
                            streams.insert(node_id, stream);
                        }

                        _ => todo!(),
                    }
                }

                (inputs, outputs)
            })
            .unwrap();

        let mut values = Vec::new();
        for (x, y) in [(1, 2), (0, 0), (1000, 2000), (12, 12)] {
            unsafe {
                let value = std::alloc::alloc(src_layout);
                assert!(!value.is_null());

                value.add(src_x_offset as usize).cast::<u32>().write(x);
                value.add(src_y_offset as usize).cast::<u32>().write(y);

                values.push((Ptr(value), 1i32));
            }
        }
        inputs.get_mut(&source).unwrap().append(&mut values);

        runtime.step().unwrap();

        let output = outputs.get(&sink).unwrap().consolidate();
        let mut cursor = output.cursor();
        while cursor.key_valid() {
            let (key, weight) = (*cursor.key(), cursor.weight());
            let key_val = unsafe { key.0.add(sink_x_offset as usize).cast::<u32>().read() };
            println!("{key_val}: {weight}");

            unsafe { std::alloc::dealloc(key.0, sink_layout) }

            cursor.step_key();
        }

        runtime.kill().unwrap();

        unsafe { jit_module.free_memory() }
    }
}