package sai

import scala.language.implicitConversions

import scalaz._
import Scalaz._

import scala.virtualization.lms.internal.GenericNestedCodegen
import scala.virtualization.lms.common.{
  SetOps => _, SetOpsExp => _, ScalaGenSetOps => _,
  ListOps => _, ListOpsExp => _, ScalaGenListOps => _,
  _}
import org.scala_lang.virtualized.virtualize
import org.scala_lang.virtualized.SourceContext

import sai.lms._
import sai.examples._

object EnvInterpreter {
  /* An environment interpreter using Reader Monad */
  import PCFLang._
  import PCFLang.Values._
  type Ident = String
  type Env = Map[Ident, Value]

  type ReaderT[F[_], A, B] = Kleisli[F, A, B]
  val ReaderT = Kleisli
  import ReaderT._

  type AnsT[T] = ReaderT[Id, Env, T]
  type Ans = AnsT[Value]

  def num(i: Int): Ans = IntV(i).asInstanceOf[Value].pure[AnsT]

  def prim(op: Symbol, v1: Value, v2: Value): Value = (op, v1, v2) match {
    case ('+, IntV(x), IntV(y)) => IntV(x + y)
    case ('-, IntV(x), IntV(y)) => IntV(x - y)
    case ('*, IntV(x), IntV(y)) => IntV(x * y)
    case ('/, IntV(x), IntV(y)) => IntV(x / y)
  }

  def ap_clo(fun: Value, arg: Value): Ans = fun match { case CloV(Lam(x, e), ρ: Env) => eval(e).local[Env](_ => ρ + (x → arg)) }

  def branch0(test: Value, thn: Expr, els: Expr): Ans =
    if (test == IntV(0)) eval(thn) else eval(els)

  def eval(e: Expr): Ans = e match {
    case Lit(i) => num(i)
    case Var(x) => for {
      ρ <- ask[Id, Env]
    } yield ρ(x)
    case Lam(x, e) => for {
      ρ <- ask[Id, Env]
    } yield CloV(Lam(x, e), ρ)
    case App(e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
      rt <- ap_clo(v1, v2)
    } yield rt
    case Let(x, rhs, e) => for {
      v <- eval(rhs)
      ρ <- ask[Id, Env]
      rt <- eval(e).local[Env](_ => ρ + (x → v))
    } yield rt
    case If0(e1, e2, e3) => for {
      cnd <- eval(e1)
      rt <- branch0(cnd, e2, e3)
    } yield rt
    case Aop(op, e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
    } yield prim(op, v1, v2)
    case Rec(x, rhs, e) => ???
  }
}

object EnvStoreInterpreter {
  /* An environment-and-store interpreter using Reader Monad and State Monad */
  import PCFLang._
  import PCFLang.Values._

  type Ident = String
  type Addr = Int
  type Env = Map[Ident, Addr]
  type Store = Map[Addr, Value]

  type ReaderT[F[_], B] = Kleisli[F, Env, B]
  val ReaderT = Kleisli

  type StateT[T] = IndexedStateT[Id, Store, Store, T]
  val StateT = IndexedStateT

  type AnsT[T] = ReaderT[StateT, T]
  type Ans = AnsT[Value]

  def ask_env: AnsT[Env] = ReaderT.ask[StateT, Env]
  def ext_env(xv: (String, Addr)): AnsT[Env] = for { ρ <- ask_env } yield ρ + xv

  def get_store: AnsT[Store] = MonadTrans[ReaderT].liftM[StateT, Store](State.get)
  def alloc(x: String): AnsT[Addr] = for { σ <- get_store } yield σ.size + 1

  def update_store(av: (Addr, Value)): AnsT[Unit] =
    MonadTrans[ReaderT].liftM[StateT, Unit](State.modify(σ => σ + av))

  def close(λ: Lam, ρ: Env): Value = CloV(λ, ρ)
  def num(i: Int): Ans = IntV(i).asInstanceOf[Value].pure[AnsT]

  def local_env(e: Expr, ρ: Env): Ans = for {
    rt <- eval(e).local[Env](_ => ρ)
  } yield rt

  def ap_clo(fun: Value, arg: Value): Ans = fun match {
    case CloV(Lam(x, e), ρ: Env) => for {
      α <- alloc(x)
      _ <- update_store(α → arg)
      ρ <- ext_env(x → α)
      rt <- local_env(e, ρ)
    } yield rt
  }

  def branch0(test: Value, thn: Expr, els: Expr): Ans =
    if (test == IntV(0)) eval(thn) else eval(els)

  def prim(op: Symbol, v1: Value, v2: Value): Value = (op, v1, v2) match {
    case ('+, IntV(x), IntV(y)) => IntV(x + y)
    case ('-, IntV(x), IntV(y)) => IntV(x - y)
    case ('*, IntV(x), IntV(y)) => IntV(x * y)
    case ('/, IntV(x), IntV(y)) => IntV(x / y)
  }

  def eval(e: Expr): Ans = e match {
    case Lit(i) => num(i)
    case Var(x) => for {
      ρ <- ask_env
      σ <- get_store
    } yield σ(ρ(x))
    case Lam(x, e) => for {
      ρ <- ask_env
    } yield close(Lam(x, e), ρ)
    case App(e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
      rt <- ap_clo(v1, v2)
    } yield rt
    case Let(x, rhs, e) => for {
      v <- eval(rhs)
      α <- alloc(x)
      _ <- update_store(α → v)
      ρ <- ext_env(x → α)
      rt <- local_env(e, ρ)
    } yield rt
    case If0(e1, e2, e3) => for {
      cnd <- eval(e1)
      rt <- branch0(cnd, e2, e3)
    } yield rt
    case Aop(op, e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
    } yield prim(op, v1, v2)
    case Rec(x, rhs, e) => for {
      α <- alloc(x)
      ρ <- ext_env(x → α)
      v <- local_env(rhs, ρ)
      _ <- update_store(α → v)
      rt <- local_env(e, ρ)
    } yield rt
  }

  val ρ0: Env = Map()
  val σ0: Store = Map()
  def run(e: Expr): (Store, Value) = eval(e).run(ρ0).run(σ0)
}

@virtualize
trait StagedCESOps extends SAIDsl {
  import PCFLang._

  sealed trait Value
  case class IntV(i: Int) extends Value
  case class CloV[Env](lam: Lam, e: Env) extends Value

  type Ident = String

  type Addr0 = Int
  type Addr = Rep[Int]

  type Env0 = Map[Ident, Addr0]
  type Env = Rep[Env0]

  type Store0 = Map[Addr0, Value]
  type Store = Rep[Store0]

  type ReaderT[F[_], B] = Kleisli[F, Env, B]
  val ReaderT = Kleisli

  type StateT[T] = IndexedStateT[Id, Store, Store, T]
  val StateT = IndexedStateT

  type AnsT[T] = ReaderT[StateT, T]
  type Ans = AnsT[Rep[Value]]

  def ask_env: AnsT[Env] = ReaderT.ask[StateT, Env]
  def ext_env(x: Rep[String], a: Addr): AnsT[Env] = for { ρ <- ask_env } yield ρ + (x → a)

  def alloc_aux(σ: Store, x: String): Rep[Addr0] = σ.size + 1
  def alloc(x: String): AnsT[Addr] = for { σ <- get_store } yield σ.size + 1

  def put_store(σ: Store): AnsT[Unit] = MonadTrans[ReaderT].liftM[StateT, Unit](State.put(σ))
  def get_store: AnsT[Store] = MonadTrans[ReaderT].liftM[StateT, Store](State.get)
  def update_store(a: Addr, v: Rep[Value]): AnsT[Unit] =
    MonadTrans[ReaderT].liftM[StateT, Unit](State.modify(σ => σ + (a → v)))

  def prim(op: Symbol, v1: Rep[Value], v2: Rep[Value]): Rep[Value] = {
    val v1n = unchecked(v1, ".asInstanceOf[IntV].i")
    val v2n = unchecked(v2, ".asInstanceOf[IntV].i")
    unchecked("IntV(", v1n, op.toString.drop(1), v2n, ")")
  }

  def local_env(e: Expr, ρ: Env): Ans = for {
    rt <- eval(e).local[Env](_ => ρ)
  } yield rt

  def branch0(test: Rep[Value], thn: Expr, els: Expr): Ans = {
    val i = unchecked[Int](test, ".asInstanceOf[IntV].i")
    for {
      ρ <- ask_env
      σ <- get_store
      val res: Rep[(Store0, Value)] =
        if (i == 0) eval(thn).run(ρ).run(σ)
        else eval(els).run(ρ).run(σ)
      _ <- put_store(res._1)
    } yield res._2
  }

  def close(λ: Lam, ρ: Env): Rep[Value] = {
    val Lam(x, e) = λ
    val f: Rep[(Store0, Value)] => Rep[(Store0, Value)] = {
      case as: Rep[(Store0, Value)] =>
        val σ = as._1; val v = as._2
        val α = alloc_aux(σ, x)
        eval(e).run(ρ + (unit(x) → α)).run(σ + (α → v))
    }
    unchecked("CompiledClo(", fun(f), ",", λ, ",", ρ, ")")
  }

  def ap_clo(fun: Rep[Value], arg: Rep[Value]): Ans

  def num(i: Int): Ans = unchecked[Value]("IntV(", i, ")").pure[AnsT]

  def eval(e: Expr): Ans = e match {
    case Lit(i) => num(i)
    case Var(x) => for {
      ρ <- ask_env
      σ <- get_store
    } yield σ(ρ(x))
    case Lam(x, e) => for {
      ρ <- ask_env
    } yield close(Lam(x, e), ρ)
    case App(e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
      rt <- ap_clo(v1, v2)
    } yield rt
    case Let(x, rhs, e) => for {
      v <- eval(rhs)
      α <- alloc(x)
      _ <- update_store(α, v)
      ρ <- ext_env(x, α)
      rt <- local_env(e, ρ)
    } yield rt
    case If0(e1, e2, e3) => for {
      cnd <- eval(e1)
      rt <- branch0(cnd, e2, e3)
    } yield rt
    case Aop(op, e1, e2) => for {
      v1 <- eval(e1)
      v2 <- eval(e2)
    } yield prim(op, v1, v2)
    case Rec(x, rhs, e) => for {
      α <- alloc(x)
      ρ <- ext_env(x, α)
      v <- local_env(rhs, ρ)
      _ <- update_store(α, v)
      rt <- local_env(e, ρ)
    } yield rt
  }

  val ρ0: Env = Map[String, Addr0]()
  val σ0: Store = Map[Addr0, Value]()

  def run(e: Expr): (Store, Rep[Value]) = eval(e).run(ρ0).run(σ0)
}

trait StagedCESOpsExp extends StagedCESOps with SAIOpsExp {
  case class ApClo(f: Rep[Value], arg: Rep[Value], σ: Store) extends Def[(Store0, Value)]

  @virtualize
  def ap_clo(fun: Rep[Value], arg: Rep[Value]): Ans = for {
    σ <- get_store
    val res: Rep[(Store0, Value)] = reflectEffect(ApClo(fun, arg, σ))
    _ <- put_store(res._1)
  } yield res._2
}

trait StagedCESGen extends GenericNestedCodegen {
  val IR: StagedCESOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case ApClo(f, arg, σ) =>
      emitValDef(sym, s"${quote(f)}.asInstanceOf[CompiledClo].f(${quote(σ)}, ${quote(arg)})")
    case Struct(tag, elems) =>
      //This fixes code generation for tuples, such as Tuple2MapIntValueValue
      //TODO: merge back to LMS
      registerStruct(structName(sym.tp), sym.tp, elems)
      val typeName = sym.tp.runtimeClass.getSimpleName +
        "[" + sym.tp.typeArguments.map(a => remap(a)).mkString(",") + "]"
      emitValDef(sym, "new " + typeName + "(" + elems.map(e => quote(e._2)).mkString(",") + ")")
    case _ => super.emitNode(sym, rhs)
  }
}

trait StagedCESDriver extends DslDriver[Unit, Unit] with StagedCESOpsExp { q =>
  override val codegen = new DslGen
      with ScalaGenMapOps
      with ScalaGenSetOps
      with ScalaGenUncheckedOps
      with SAI_ScalaGenTupleOps
      with SAI_ScalaGenTupledFunctions
      with StagedCESGen {
    val IR: q.type = q

    override def remap[A](m: Manifest[A]): String = {
      if (m.toString.endsWith("$Value")) "Value"
      else super.remap(m)
    }
    override def emitSource[A : Manifest](args: List[Sym[_]], body: Block[A], className: String,
                                          stream: java.io.PrintWriter): List[(Sym[Any], Any)] = {
      val prelude = """
  import sai.PCFLang._
  sealed trait Value
  case class IntV(i: Int) extends Value
  case class CompiledClo(f: (Map[Int,Value], Value) => (Map[Int,Value], Value), λ: Lam, ρ: Map[String,Int]) extends Value
        """
      stream.println(prelude)
      super.emitSource(args, body, className, stream)
    }
  }
}

object AbsInterpreter {
  import PCFLang._

  trait AbsValue
  case object IntTop extends AbsValue
  case class CloV[Env](lam: Lam, env: Env) extends AbsValue

  type Ident = String
  case class Addr(x: String)
  type Env = Map[Ident, Addr]
  type Store = Map[Addr, Value]

  type ReaderT[F[_], B] = Kleisli[F, Env, B]
  type StateT[F[_], B] = IndexedStateT[F, Store, Store, B]
  type NondetT[T] = ListT[Id, T]

  type StateNondetT[T] = StateT[NondetT, T]
  type AnsT[T] = ReaderT[StateNondetT, T]
  type Ans = AnsT[Value]

  def prim(op: Symbol, v1: Value, v2: Value): Value = (op, v1, v2) match {
    case ('+, IntV(x), IntV(y)) => IntV(x + y)
    case ('-, IntV(x), IntV(y)) => IntV(x - y)
    case ('*, IntV(x), IntV(y)) => IntV(x * y)
    case ('/, IntV(x), IntV(y)) => IntV(x / y)
  }

  def num(i: Int): Ans = IntTop.asInstanceOf[Value].pure[AnsT]

  def eval(e: Expr): Ans = e match {
    case Lit(i) => num(i)
    case Amb(e1, e2) =>
    ???
    case Aop(op, e1, e2) => ???
  }
}

object Main {
  import PCFLang._

  def specialize(e: Expr): DslDriver[Unit, Unit] = new StagedCESDriver {
    @virtualize
    def snippet(unit: Rep[Unit]): Rep[Unit] = {
      val (s, v) = run(e)
      println(s)
      println(v)
    }
  }

  def main(args: Array[String]): Unit = {
    val code = specialize(fact5)
    println(code.code)
    code.eval(())

    //val s = new Snippet()
    //println(s(()))
  }
}
