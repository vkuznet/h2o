package hex.glm;

import hex.gram.Gram;
import hex.gram.Gram.Cholesky;

import java.util.ArrayList;
import java.util.Arrays;

import jsr166y.RecursiveAction;
import water.Iced;
import water.Key;
import water.MemoryManager;
import water.util.Log;
import water.util.Utils;

import com.google.gson.JsonObject;


/**
 * Distributed least squares solvers
 * @author tomasnykodym
 *
 */
public abstract class LSMSolver extends Iced{

  public enum LSMSolverType {
    AUTO, // AUTO: (len(beta) < 1000)?ADMM:GenGradient
    ADMM,
    GenGradient
  }
  double _lambda;
  final double _alpha;
  public Key _jobKey;
  public String _id;

  public LSMSolver(double lambda, double alpha){
    _lambda = lambda;
    _alpha  = alpha;
  }

  public final double [] grad(Gram gram, double [] beta, double [] xy){
    double [] grad = gram.mul(beta);
    for(int i = 0; i < grad.length; ++i)
      grad[i] -= xy[i];
    return grad;
  }


  public static void subgrad(final double alpha, final double lambda, final double [] beta, final double [] grad){
    final double l1pen = lambda*alpha;
    for(int i = 0; i < grad.length-1; ++i) {// add l2 reg. term to the gradient
      if(beta[i] < 0) grad[i] -= l1pen;
      else if(beta[i] > 0) grad[i] += l1pen;
      else grad[i] = LSMSolver.shrinkage(grad[i], l1pen);
    }
  }

  /**
   *  @param xy - guassian: -X'y binomial: -(1/4)X'(XB + (y-p)/(p*1-p))
   *  @param yy - &lt; y,y &gt; /2
   *  @param newBeta - resulting vector of coefficients
   *  @return true if converged
   *
   */
  public abstract boolean solve(Gram gram, double [] xy, double yy, double [] newBeta);

  protected boolean _converged;

  public final boolean converged(){return _converged;}
  public static class LSMSolverException extends RuntimeException {
    public LSMSolverException(String msg){super(msg);}
  }
  public abstract String name();


  protected static double shrinkage(double x, double kappa) {
    return Math.max(0, x - kappa) - Math.max(0, -x - kappa);
  }

  /**
   * Compute least squares objective function value:
   *    lsm_obj(beta) = 0.5*(y - X*b)'*(y - X*b) + l1 + l2
   *                  = 0.5*y'y - (X'y)'*b + 0.5*b'*X'X*b) + l1 + l2
   *    l1 = alpha*lambda*l1norm(beta)
   *    l2 = (1-alpha)*lambda*l2norm(beta)/2
   * @param xy:   X'y
   * @param yy:   0.5*y'y
   * @param beta: b (vector of coefficients)
   * @param xb: X'X*beta
   * @return 0.5*(y - X*b)'*(y - X*b) + l1 + l2
   */
  protected double objectiveVal(double[] xy, double yy, double[] beta, double [] xb) {
    double res = lsm_objectiveVal(xy,yy,beta, xb);
    double l1 = 0, l2 = 0;
    for(int i = 0; i < beta.length; ++i){
      l1 += Math.abs(beta[i]);
      l2 += beta[i]*beta[i];
    }
    return res + _alpha*_lambda*l1 + 0.5*(1-_alpha)*_lambda*l2;
  }

  /**
   * Compute the LSM objective.
   *
   *   lsm_obj(beta) = 0.5 * (y - X*b)' * (y - X*b)
   *                 = 0.5 * y'y - (X'y)'*b + 0.5*b'*X'X*b)
   *                 = 0.5yy + b*(0.5*X'X*b - X'y)
   * @param xy X'y
   * @param yy y'y
   * @param beta
   * @param xb X'X*beta
   * @return
   */
  protected double lsm_objectiveVal(double[] xy, double yy, double[] beta, double [] xb) {
    double res = 0.5*yy;
    for(int i = 0; i < xb.length; ++i)
      res += beta[i]*(0.5*xb[i] - xy[i]);
    return res;
  }

  static final double[] mul(double[][] X, double[] y, double[] z) {
    final int M = X.length;
    final int N = y.length;
    for( int i = 0; i < M; ++i ) {
      z[i] = X[i][0] * y[0];
      for( int j = 1; j < N; ++j )
        z[i] += X[i][j] * y[j];
    }
    return z;
  }

  static final double[] mul(double[] x, double a, double[] z) {
    for( int i = 0; i < x.length; ++i )
      z[i] = a * x[i];
    return z;
  }

  static final double[] plus(double[] x, double[] y, double[] z) {
    for( int i = 0; i < x.length; ++i )
      z[i] = x[i] + y[i];
    return z;
  }

  static final double[] minus(double[] x, double[] y, double[] z) {
    for( int i = 0; i < x.length; ++i )
      z[i] = x[i] - y[i];
    return z;
  }

  static final double[] shrink(double[] x, double[] z, double kappa) {
    for( int i = 0; i < x.length - 1; ++i )
      z[i] = shrinkage(x[i], kappa);
    z[x.length - 1] = x[x.length - 1]; // do not penalize intercept!
    return z;
  }



  public static final class ADMMSolver extends LSMSolver {
    //public static final double DEFAULT_LAMBDA = 1e-5;
    public static final double DEFAULT_ALPHA = 0.5;
    public double _orlx = 1;//1.4; // over relaxation param
    public double _rho = Double.NaN;
    public double [] _wgiven;
    public double _proximalPenalty;
    final public double _gradientEps;
    private static final double GLM1_RHO = 1.0e-3;

    public boolean normalize() {return _lambda != 0;}

    public double _addedL2;
    public ADMMSolver (double lambda, double alpha, double gradEps) {
      super(lambda,alpha);
      _gradientEps = gradEps;
    }
    public ADMMSolver (double lambda, double alpha, double gradEps,double addedL2) {
      super(lambda,alpha);
      _addedL2 = addedL2;
      _gradientEps = gradEps;
    }

    public JsonObject toJson(){
      JsonObject res = new JsonObject();
      res.addProperty("lambda",_lambda);
      res.addProperty("alpha",_alpha);
      return res;
    }

    public static class NonSPDMatrixException extends LSMSolverException {
      public NonSPDMatrixException(){super("Matrix is not SPD, can't solve without regularization\n");}
      public NonSPDMatrixException(Gram grm){

        super("Matrix is not SPD, can't solve without regularization\n" + grm);
      }
    }

    @Override
    public boolean solve(Gram gram, double [] xy, double yy, double[] z) {
      return solve(gram, xy, yy, z, Double.POSITIVE_INFINITY);
    }

    private static double l1_norm(double [] v){
      double res = 0;
      for(double d:v)res += Math.abs(d);
      return res;
    }
    private static double l2_norm(double [] v){
      double res = 0;
      for(double d:v)res += d*d;
      return res;
    }

    private double converged(Gram g, double [] beta, double [] xy){
      double [] grad = grad(g,beta,xy);
      subgrad(_alpha,_lambda,beta,grad);
      double err = 0;
      for(double d:grad)
        if(d > err)err = d;
        else if(d < -err)err = -d;
      return err;
    }

    public double grad_err = Double.POSITIVE_INFINITY;

    private double getGrad(int i, Gram gram, double [] beta, double [] xy){
      double [] g = grad(gram,beta,xy);
      subgrad(_alpha, _lambda, beta, g);
      double err = 0;
      for(double d3:g)
        if(d3 > err)err = d3;
        else if(d3 < -err)err = -d3;
      return err;
    }

    public boolean solve(Gram gram, double [] xy, double yy, double[] res, double objVal) {
      double d = gram._diagAdded;
      final int N = xy.length;
      Arrays.fill(res, 0);
      if(_lambda>0 || _addedL2 > 0)
        gram.addDiag(_lambda*(1-_alpha) + _addedL2);
      double rho = _rho;
      if(_alpha > 0 && _lambda > 0){
        if(Double.isNaN(_rho)) rho = _lambda*_alpha;// find rho value as min diag element + constant
        System.out.println("rho = " + rho);
        gram.addDiag(rho);
      }
      if(_proximalPenalty > 0 && _wgiven != null){
        gram.addDiag(_proximalPenalty, true);
        xy = xy.clone();
        for(int i = 0; i < xy.length; ++i)
          xy[i] += _proximalPenalty*_wgiven[i];
      }
      int attempts = 0;
      long t1 = System.currentTimeMillis();
      Cholesky chol = gram.cholesky(null,true,_id);
      long t2 = System.currentTimeMillis();
      while(!chol.isSPD() && attempts < 10){
        if(_addedL2 == 0) _addedL2 = 1e-5;
        else _addedL2 *= 10;
        Log.info("GLM ADMM: BUMPED UP RHO TO " + rho + _addedL2);
        ++attempts;
        gram.addDiag(_addedL2); // try to add L2 penalty to make the Gram issp
        gram.cholesky(chol);
      }
      long decompTIme = (t2-t1);

      if(!chol.isSPD()){
        System.out.println("can not solve, got non-spd matrix and adding regularization did not help, matrix = \n" + gram);
        throw new NonSPDMatrixException(gram);
      }
      _rho = rho;
      if(_alpha == 0 || _lambda == 0){ // no l1 penalty
        System.arraycopy(xy, 0, res, 0, xy.length);
        chol.solve(res);
        gram.addDiag(-gram._diagAdded + d);
        return true;
      }
      long t = System.currentTimeMillis();
      final double ABSTOL = Math.sqrt(N) * 1e-4;
      final double RELTOL = 1e-2;
      double[] u = MemoryManager.malloc8d(N);
      double [] xyPrime = xy.clone();
      double kappa = _lambda*_alpha/rho;
      double [] grad = null;
      int i;
      int k = 10;
      double gradientErr = Double.POSITIVE_INFINITY;

      double gerr = Double.POSITIVE_INFINITY;
      double [] z = res.clone();
      for(i = 0; i < 2500; ++i ) {
        // first compute the x update
        // add rho*(z-u) to A'*y
        for( int j = 0; j < N-1; ++j )xyPrime[j] = xy[j] + rho*(z[j] - u[j]);
        xyPrime[N-1] = xy[N-1];
        // updated x
        chol.solve(xyPrime);
        // compute u and z update
        for( int j = 0; j < N-1; ++j ) {
          double x_hat = xyPrime[j];
          x_hat = x_hat * _orlx + (1 - _orlx) * z[j];
          double zold = z[j];
          z[j] = shrinkage(x_hat + u[j], kappa);
          u[j] += x_hat - z[j];
        }
        z[N-1] = xyPrime[N-1];
        if(i == k){
          gerr = getGrad(i,gram,z,xy);
          if(gerr < _gradientEps){
            _converged = true;
            System.arraycopy(z,0,res,0,z.length);
            break;
          }
          // did not converge, check if we can converge in reasonable time
          double diff = gradientErr - gerr;
          if(diff < 0 || (gerr/diff) > 1e3){ // we won't ever converge with this setup (maybe change rho and try again?)
            if(_orlx < 1.8 && gerr > 5e-4) {
              _orlx = 1.8; // try if over-relaxation helps...
            } else {
              _converged = gerr < 1e-4;
              break;
            }
          } else {
            System.arraycopy(z,0,res,0,z.length);
            gradientErr = gerr;
          }
          k = i + 10; // test gradient every 10 iterations
        }
      }
      gram.addDiag(-gram._diagAdded + d);
      assert gram._diagAdded == d;
      long solveTime = System.currentTimeMillis()-t;
      if(Double.isInfinite(gradientErr)) gradientErr = getGrad(i,gram,res,xy);
      Log.info("ADMM finished in " + i + " iterations and (" + decompTIme + " + " + solveTime+ ")ms, max |subgradient| = " + gradientErr);
      return _converged;
    }
    @Override
    public String name() {return "ADMM";}
  }

  public static final class ProxSolver extends LSMSolver {
    public ProxSolver(double lambda, double alpha){super(lambda,alpha);}

    /**
     * @param newB
     * @param oldObj
     * @param oldB
     * @param
     * @param t
     * @return
     */
    private static final double f_hat(double [] newB,double oldObj, double [] oldB,double [] xb, double [] xy, double t){
      double res = oldObj;
      double l2 = 0;
      for(int i = 0; i < newB.length; ++i){
        double diff = newB[i] - oldB[i];
        res += (xb[i]-xy[i])*diff;
        l2 += diff*diff;
      }
      return res + 0.25*l2/t;
    }
  private double penalty(double [] beta){
    double l1 = 0,l2 = 0;
    for(int i = 0; i < beta.length; ++i){
      l1 += Math.abs(beta[i]);
      l2 += beta[i]*beta[i];
    }
    return _lambda*(_alpha*l1 + (1-_alpha)*l2*0.5);
  }
    private static double betaDiff(double [] b1, double [] b2){
      double res = 0;
      for(int i = 0; i < b1.length; ++i)
        Math.max(res, Math.abs(b1[i] - b2[i]));
      return res;
    }
    @Override
    public boolean solve(Gram gram, double [] xy, double yy, double[] beta) {
      ADMMSolver admm = new ADMMSolver(_lambda,_alpha,1e-2);
      if(gram != null)return admm.solve(gram,xy,yy,beta);
      Arrays.fill(beta,0);
      long t1 = System.currentTimeMillis();
      final double [] xb = gram.mul(beta);
      double objval = objectiveVal(xy,yy,beta,xb);
      final double [] newB = MemoryManager.malloc8d(beta.length);
      final double [] newG = MemoryManager.malloc8d(beta.length);
      double step = 1;
      final double l1pen = _lambda*_alpha;
      final double l2pen = _lambda*(1-_alpha);
      double lsmobjval = lsm_objectiveVal(xy,yy,beta,xb);
      boolean converged = false;
      final int intercept = beta.length-1;
      int iter = 0;
      MAIN:
      while(!converged && iter < 1000) {
        ++iter;
        step = 1;
        while(step > 1e-12){ // line search
          double l2shrink = 1/(1+step*l2pen);
          double l1shrink = l1pen*step;
          for(int i = 0; i < beta.length-1; ++i)
            newB[i] = l2shrink*shrinkage((beta[i]-step*(xb[i]-xy[i])),l1shrink);
          newB[intercept] = beta[intercept] - step*(xb[intercept]-xy[intercept]);
          gram.mul(newB, newG);
          double newlsmobj = lsm_objectiveVal(xy, yy, newB,newG);
          double fhat = f_hat(newB,lsmobjval,beta,xb,xy,step);
          if(newlsmobj <= fhat){
            lsmobjval = newlsmobj;
            converged = betaDiff(beta,newB) < 1e-6;
            System.arraycopy(newB,0,beta,0,newB.length);
            System.arraycopy(newG,0,xb,0,newG.length);
            continue MAIN;
          } else step *= 0.8;
        }
        converged = true;
      }
      return converged;
    }
    public String name(){return "ProximalGradientSolver";}
  }
}
